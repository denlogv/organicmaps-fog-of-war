package app.organicmaps;

import android.content.Context;
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
import app.organicmaps.widget.placepage.CurrentLocationMarkerView;
import app.organicmaps.widget.placepage.ElevationChartUtils;
import app.organicmaps.widget.placepage.ElevationProfileChart;
import app.organicmaps.widget.placepage.FloatingMarkerView;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
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
  private static final int CURRENT_POSITION_OUT_OF_TRACK = -1;
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
    mChart = view.findViewById(R.id.elevation_profile_chart);

    mFloatingMarkerView = view.findViewById(R.id.floating_marker);
    mCurrentLocationMarkerView = new CurrentLocationMarkerView(mContext);
    mFloatingMarkerView.setChartView(mChart);
    mCurrentLocationMarkerView.setChartView(mChart);

    mMaxAltitude = view.findViewById(R.id.highest_altitude);
    mMinAltitude = view.findViewById(R.id.lowest_altitude);

    ElevationChartUtils.setupTrackChart(mChart, mContext);
    mChart.setOnChartValueSelectedListener(this);
    mChart.setOnChartGestureListener(this);
    mChart.setRangeListener(this);
  }

  public void setViewModel(@Nullable PlacePageViewModel viewModel)
  {
    mViewModel = viewModel;
  }

  public void setData(@Nullable Track track, @NonNull ElevationInfo info, @NonNull TrackStatistics stats)
  {
    mTrack = track;
    mFirstSelectionDist = -1.0;
    clearRangeSelection();

    List<Entry> values = new ArrayList<>();
    for (ElevationInfo.Point point : info.getPoints())
      values.add(new Entry((float) point.getDistance(), point.getAltitude()));
    mAllEntries = values;

    ElevationChartUtils.configureYAxisBounds(mChart, stats.getMinElevation(), stats.getMaxElevation());
    ElevationChartUtils.addSegmentSeparators(mChart, info.getSegmentDistances(), mContext);
    ElevationChartUtils.setChartData(mChart, values, mContext);

    mMinAltitude.setText(Framework.nativeFormatAltitude(stats.getMinElevation()));
    mMaxAltitude.setText(Framework.nativeFormatAltitude(stats.getMaxElevation()));

    if (track != null)
    {
      // The core only pushes the current position when it moves, ask for it once upfront so the
      // marker shows up right away on a freshly opened track.
      mCurrentPositionOutOfTrack = track.getElevationCurPositionDistance() == CURRENT_POSITION_OUT_OF_TRACK;
      highlightActivePointManually();
    }
    mChart.setTouchEnabled(mTrack != null);
  }

  private float interpolateAltitude(float distance)
  {
    if (mChart.getData() == null || mChart.getData().getDataSetCount() == 0)
      return 0f;
    if (!(mChart.getData().getDataSetByIndex(0) instanceof LineDataSet set))
      return 0f;
    return ElevationChartUtils.interpolateY(set.getValues(), distance);
  }

  private void selectAtDistance(float distance, boolean informCore)
  {
    float altitude = interpolateAltitude(distance);
    Entry interpolated = new Entry(distance, altitude);
    Highlight h = new Highlight(distance, altitude, 0);

    mFloatingMarkerView.updateOffsets(interpolated, h);
    if (mTrack == null)
      return;

    Highlight curPos = getCurrentPosHighlight();

    if (mCurrentPositionOutOfTrack)
      mChart.highlightValues(Collections.singletonList(h), Collections.singletonList(mFloatingMarkerView));
    else
      mChart.highlightValues(Arrays.asList(curPos, h), Arrays.asList(mCurrentLocationMarkerView, mFloatingMarkerView));

    mFirstSelectionDist = distance;

    if (informCore)
      BookmarkManager.INSTANCE.setElevationActivePoint(mTrack.getTrackId(), distance);
  }

  @Override
  public void onValueSelected(Entry e, Highlight h)
  {
    selectAtDistance(h.getX(), mInformSelectedActivePointToCore);
    mInformSelectedActivePointToCore = true;
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
    if (mTrack != null)
      BookmarkManager.INSTANCE.setElevationActivePoint(mTrack.getTrackId(), moved.getX());
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

  @NonNull
  private Highlight getCurrentPosHighlight()
  {
    float distance = (float) mTrack.getElevationCurPositionDistance();
    return new Highlight(distance, interpolateAltitude(distance), 0);
  }

  @Override
  public void onNothingSelected()
  {
    if (mCurrentPositionOutOfTrack)
      return;

    highlightChartCurrentLocation();
  }

  public void onCurrentPositionChanged(double distance)
  {
    if (mTrack == null)
      return;

    mCurrentPositionOutOfTrack = distance == CURRENT_POSITION_OUT_OF_TRACK;
    highlightActivePointManually();
  }

  public void onElevationActivePointChanged(double distance)
  {
    if (mTrack == null)
      return;

    highlightActivePointManually((float) distance);
  }

  private void highlightActivePointManually()
  {
    Highlight highlight = getActivePoint();
    highlightActivePointManually(highlight);
  }

  private void highlightActivePointManually(float distance)
  {
    Highlight highlight = getActivePoint(distance);
    highlightActivePointManually(highlight);
  }

  private void highlightActivePointManually(@NonNull Highlight highlight)
  {
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
    return getActivePoint((float) mTrack.getElevationActivePointDistance());
  }

  @NonNull
  private Highlight getActivePoint(float distance)
  {
    return new Highlight(distance, interpolateAltitude(distance), 0);
  }
}
