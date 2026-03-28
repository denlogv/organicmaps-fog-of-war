package app.organicmaps;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.ElevationInfo;
import app.organicmaps.sdk.bookmarks.data.Track;
import app.organicmaps.sdk.bookmarks.data.TrackStatistics;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.widget.placepage.AxisValueFormatter;
import app.organicmaps.widget.placepage.CurrentLocationMarkerView;
import app.organicmaps.widget.placepage.ElevationProfileChart;
import app.organicmaps.widget.placepage.FloatingMarkerView;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChartController
    implements OnChartValueSelectedListener, OnChartGestureListener, ElevationProfileChart.RangeListener
{
  private static final int CHART_Y_LABEL_COUNT = 3;
  private static final int CHART_X_LABEL_COUNT = 6;
  private static final int CHART_ANIMATION_DURATION = 0;
  private static final int CHART_FILL_ALPHA = (int) (0.12 * 255);
  private static final int CHART_AXIS_GRANULARITY = 100;
  private static final int CURRENT_POSITION_OUT_OF_TRACK = -1;
  private static final String ELEVATION_PROFILE_POINTS = "ELEVATION_PROFILE_POINTS";
  private static final String SELECTION_RANGE_POINTS = "SELECTION_RANGE_POINTS";
  private static final int SELECTION_FILL_ALPHA = (int) (0.25 * 255);

  @NonNull
  private final Context mContext;
  @NonNull
  private final ElevationProfileChart mChart;
  @NonNull
  private final FloatingMarkerView mFloatingMarkerView;
  @NonNull
  private final MarkerView mCurrentLocationMarkerView;
  @NonNull
  private final TextView mMaxAltitude;
  @NonNull
  private final TextView mMinAltitude;

  @Nullable
  private Track mTrack;
  @Nullable
  private PlacePageViewModel mViewModel;
  @Nullable
  private List<Entry> mAllEntries;

  private boolean mCurrentPositionOutOfTrack = true;
  private boolean mInformSelectedActivePointToCore = true;
  // Distance of the first selection point (the active point), or -1 if no active point.
  private double mFirstSelectionDist = -1.0;

  public ChartController(@NonNull View view)
  {
    mContext = view.getContext();
    final Resources resources = mContext.getResources();
    mChart = view.findViewById(R.id.elevation_profile_chart);

    mFloatingMarkerView = view.findViewById(R.id.floating_marker);
    mCurrentLocationMarkerView = new CurrentLocationMarkerView(mContext);
    mFloatingMarkerView.setChartView(mChart);
    mCurrentLocationMarkerView.setChartView(mChart);

    mMaxAltitude = view.findViewById(R.id.highest_altitude);
    mMinAltitude = view.findViewById(R.id.lowest_altitude);

    mChart.setBackgroundColor(ThemeUtils.getColor(mContext, R.attr.cardBackground));
    mChart.setTouchEnabled(true);
    mChart.setOnChartValueSelectedListener(this);
    mChart.setOnChartGestureListener(this);
    mChart.setRangeListener(this);
    mChart.setDrawGridBackground(false);
    mChart.setScaleXEnabled(true);
    mChart.setScaleYEnabled(false);
    mChart.setExtraTopOffset(0);
    int sideOffset = resources.getDimensionPixelSize(R.dimen.margin_base);
    int topOffset = 0;
    mChart.setViewPortOffsets(sideOffset, topOffset, sideOffset,
                              resources.getDimensionPixelSize(R.dimen.margin_base_plus_quarter));
    mChart.getDescription().setEnabled(false);
    mChart.setDrawBorders(false);
    Legend l = mChart.getLegend();
    l.setEnabled(false);
    initAxises();
  }

  public void setViewModel(@Nullable PlacePageViewModel viewModel)
  {
    mViewModel = viewModel;
  }

  private void initAxises()
  {
    XAxis x = mChart.getXAxis();
    x.setLabelCount(CHART_X_LABEL_COUNT, false);
    x.setDrawGridLines(false);
    x.setGranularity(CHART_AXIS_GRANULARITY);
    x.setGranularityEnabled(true);
    x.setTextColor(ThemeUtils.getColor(mContext, R.attr.elevationProfileAxisLabelColor));
    x.setPosition(XAxis.XAxisPosition.BOTTOM);
    x.setAxisLineColor(ThemeUtils.getColor(mContext, androidx.appcompat.R.attr.dividerHorizontal));
    x.setAxisLineWidth(mContext.getResources().getDimensionPixelSize(R.dimen.divider_height));
    ValueFormatter xAxisFormatter = new AxisValueFormatter(mChart);
    x.setValueFormatter(xAxisFormatter);

    YAxis y = mChart.getAxisLeft();
    y.setLabelCount(CHART_Y_LABEL_COUNT, false);
    y.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
    y.setDrawGridLines(true);
    y.setGridColor(ContextCompat.getColor(mContext, R.color.black_12));
    y.setEnabled(true);
    y.setTextColor(Color.TRANSPARENT);
    y.setAxisLineColor(Color.TRANSPARENT);
    int lineLength = mContext.getResources().getDimensionPixelSize(R.dimen.margin_eighth);
    y.enableGridDashedLine(lineLength, 2 * lineLength, 0);

    mChart.getAxisRight().setEnabled(false);
  }

  public void setData(@Nullable Track track, @NonNull ElevationInfo info, @NonNull TrackStatistics stats)
  {
    mTrack = track;
    mFirstSelectionDist = -1.0;
    clearRangeSelection();

    List<Entry> values = new ArrayList<>();
    for (ElevationInfo.Point point : info.getPoints())
      values.add(new Entry((float) point.getDistance(), point.getAltitude(), point));
    mAllEntries = values;

    LineDataSet set = new LineDataSet(values, ELEVATION_PROFILE_POINTS);
    set.setMode(LineDataSet.Mode.LINEAR);
    set.setDrawFilled(true);
    set.setDrawCircles(false);
    int lineThickness = mContext.getResources().getDimensionPixelSize(R.dimen.divider_width);
    set.setLineWidth(lineThickness);
    int color = ThemeUtils.getColor(mContext, R.attr.elevationProfileColor);
    set.setCircleColor(color);
    set.setColor(color);
    set.setFillAlpha(CHART_FILL_ALPHA);
    set.setFillColor(color);
    set.setDrawHorizontalHighlightIndicator(false);
    set.setHighlightLineWidth(lineThickness);
    set.setHighLightColor(ContextCompat.getColor(mContext, R.color.base_accent_transparent));

    LineData data = new LineData(set);
    data.setValueTextSize(mContext.getResources().getDimensionPixelSize(R.dimen.text_size_icon_title));
    data.setDrawValues(false);

    mChart.setData(data);
    mChart.animateX(CHART_ANIMATION_DURATION);

    mMinAltitude.setText(Framework.nativeFormatAltitude(stats.getMinElevation()));
    mMaxAltitude.setText(Framework.nativeFormatAltitude(stats.getMaxElevation()));

    if (track != null)
      highlightActivePointManually();
    mChart.setTouchEnabled(mTrack != null);
  }

  @Override
  public void onValueSelected(Entry e, Highlight h)
  {
    mFloatingMarkerView.updateOffsets(e, h);
    if (mTrack == null)
      return;

    Highlight curPos = getCurrentPosHighlight();

    if (mCurrentPositionOutOfTrack)
      mChart.highlightValues(Collections.singletonList(h), Collections.singletonList(mFloatingMarkerView));
    else
      mChart.highlightValues(Arrays.asList(curPos, h), Arrays.asList(mCurrentLocationMarkerView, mFloatingMarkerView));

    mFirstSelectionDist = e.getX();

    if (mInformSelectedActivePointToCore)
      BookmarkManager.INSTANCE.setElevationActivePoint(mTrack.getTrackId(), e.getX(),
                                                       (ElevationInfo.Point) e.getData());
    mInformSelectedActivePointToCore = true;
  }

  @NonNull
  private Highlight getCurrentPosHighlight()
  {
    return new Highlight((float) mTrack.getElevationCurPositionDistance(), 0f, 0);
  }

  @Override
  public void onNothingSelected()
  {
    if (mCurrentPositionOutOfTrack)
      return;

    highlightChartCurrentLocation();
  }

  // OnChartGestureListener — long press starts a range from the active point to the pressed point.
  // After that, both ends are adjusted by dragging the range handles.
  @Override
  public void onChartLongPressed(MotionEvent me)
  {
    if (mTrack == null || mFirstSelectionDist < 0 || mAllEntries == null || mAllEntries.isEmpty())
      return;

    Highlight h = mChart.getHighlightByTouchPoint(me.getX(), me.getY());
    if (h == null)
      return;

    double secondDist = h.getX();
    if (secondDist == mFirstSelectionDist)
      return;

    final float minDist = (float) Math.min(mFirstSelectionDist, secondDist);
    final float maxDist = (float) Math.max(mFirstSelectionDist, secondDist);
    mChart.setRange(minDist, maxDist);
    updateRangeFill(minDist, maxDist);
    if (mViewModel != null)
      mViewModel.setTrackSelectionRange(minDist, maxDist);
  }

  @Override
  public void onRangeDragged(@NonNull Entry start, @NonNull Entry end, @NonNull Entry moved)
  {
    updateRangeFill(start.getX(), end.getX());
    // Let the user see on the map where the dragged boundary is.
    if (mTrack != null && moved.getData() instanceof ElevationInfo.Point)
      BookmarkManager.INSTANCE.setElevationActivePoint(mTrack.getTrackId(), moved.getX(),
                                                       (ElevationInfo.Point) moved.getData());
  }

  @Override
  public void onRangeDragFinished(@NonNull Entry start, @NonNull Entry end)
  {
    if (mViewModel != null)
      mViewModel.setTrackSelectionRange(start.getX(), end.getX());
  }

  @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture g) {}
  @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture g) {}
  @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float vX, float vY) {}
  @Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) {}
  @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
  @Override public void onChartDoubleTapped(MotionEvent me) {}
  @Override public void onChartSingleTapped(MotionEvent me) {}

  private void updateRangeFill(float minDist, float maxDist)
  {
    if (mAllEntries == null || mAllEntries.isEmpty())
      return;

    final int selColor = ContextCompat.getColor(mContext, R.color.track_edit_handle);

    LineData data = mChart.getData();
    if (data == null)
      return;

    int idx = -1;
    {
      ILineDataSet ds = data.getDataSetByLabel(SELECTION_RANGE_POINTS, true);
      if (ds != null)
        idx = data.getIndexOfDataSet(ds);
    }
    if (idx >= 0)
      data.removeDataSet(idx);

    List<Entry> selEntries = new ArrayList<>();
    for (Entry e : mAllEntries)
    {
      if (e.getX() >= minDist && e.getX() <= maxDist)
        selEntries.add(new Entry(e.getX(), e.getY()));
    }

    if (!selEntries.isEmpty())
    {
      LineDataSet selSet = new LineDataSet(selEntries, SELECTION_RANGE_POINTS);
      selSet.setMode(LineDataSet.Mode.LINEAR);
      selSet.setDrawFilled(true);
      selSet.setDrawCircles(false);
      selSet.setDrawValues(false);
      selSet.setLineWidth(0f);
      selSet.setColor(selColor);
      selSet.setFillAlpha(SELECTION_FILL_ALPHA);
      selSet.setFillColor(selColor);
      selSet.setHighlightEnabled(false);
      data.addDataSet(selSet);
    }

    mChart.invalidate();
  }

  private void clearRangeSelection()
  {
    if (mViewModel != null)
      mViewModel.clearTrackSelectionRange();

    mChart.clearRange();
    LineData data = mChart.getData();
    if (data != null)
    {
      int idx = -1;
      {
        ILineDataSet ds = data.getDataSetByLabel(SELECTION_RANGE_POINTS, true);
        if (ds != null)
          idx = data.getIndexOfDataSet(ds);
      }
      if (idx >= 0)
      {
        data.removeDataSet(idx);
        mChart.invalidate();
      }
    }
  }

  public void onCurrentPositionChanged()
  {
    if (mTrack == null)
      return;

    final double distance = mTrack.getElevationCurPositionDistance();
    mCurrentPositionOutOfTrack = distance == CURRENT_POSITION_OUT_OF_TRACK;
    highlightActivePointManually();
  }

  public void onElevationActivePointChanged()
  {
    if (mTrack == null)
      return;

    highlightActivePointManually();
  }

  private void highlightActivePointManually()
  {
    Highlight highlight = getActivePoint();
    mInformSelectedActivePointToCore = false;
    mChart.highlightValue(highlight, true);
  }

  private void highlightChartCurrentLocation()
  {
    mChart.highlightValues(Collections.singletonList(getCurrentPosHighlight()),
                           Collections.singletonList(mCurrentLocationMarkerView));
  }

  @NonNull
  private Highlight getActivePoint()
  {
    double activeX = mTrack.getElevationActivePointDistance();
    return new Highlight((float) activeX, 0f, 0);
  }
}
