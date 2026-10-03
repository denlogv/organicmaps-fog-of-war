package app.organicmaps.widget.placepage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.util.StringUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.DataSet;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.MPPointD;

/**
 * Elevation profile chart that can additionally show a draggable range [start, end] used by the track editor.
 * The range is drawn as two orange "bracket" handles, deliberately unlike the blue elevation marker.
 */
public class ElevationProfileChart extends LineChart
{
  public interface RangeListener
  {
    /** Called while a handle is being dragged. {@code moved} is the entry the handle currently snapped to. */
    void onRangeDragged(@NonNull Entry start, @NonNull Entry end, @NonNull Entry moved);

    /** Called when the user releases a handle. */
    void onRangeDragFinished(@NonNull Entry start, @NonNull Entry end);
  }

  private enum Handle
  {
    START,
    END
  }

  private static final float LINE_WIDTH_DP = 2f;
  private static final float GRIP_CORNER_DP = 6f;
  private static final float GRIP_TOP_MARGIN_DP = 4f;
  private static final float GRIP_LINE_DP = 1.5f;
  private static final float GRIP_LINE_HEIGHT_DP = 12f;
  private static final float LABEL_TEXT_SP = 12f;
  private static final float LABEL_PADDING_DP = 6f;

  @Nullable
  private Entry mRangeStart;
  @Nullable
  private Entry mRangeEnd;
  @Nullable
  private Handle mDragged;
  // Horizontal distance between the finger and the dragged handle line at the moment of touch-down.
  private float mGrabOffsetPx;
  @Nullable
  private RangeListener mRangeListener;

  private final float mDensity;
  private final float mGripWidth;
  private final float mGripHeight;
  private final float mTouchOuter;
  private final float mTouchInner;
  private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mGripMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF mTmpRect = new RectF();

  private static final float CAPTURE_RADIUS_DP = 22f;

  private boolean mIsSelecting;
  private boolean mSelectConfirmed;
  private float mMarkerScreenX;
  private float mTouchStartX;
  private float mLastHighlightedX = Float.NaN;
  private int mTouchSlop;

  public ElevationProfileChart(Context context)
  {
    this(context, null);
  }

  public ElevationProfileChart(Context context, AttributeSet attrs)
  {
    this(context, attrs, 0);
  }

  public ElevationProfileChart(Context context, AttributeSet attrs, int defStyle)
  {
    super(context, attrs, defStyle);
    final Resources res = context.getResources();
    mDensity = res.getDisplayMetrics().density;
    mGripWidth = res.getDimension(R.dimen.elevation_profile_range_handle_width);
    mGripHeight = res.getDimension(R.dimen.elevation_profile_range_handle_height);
    mTouchOuter = res.getDimension(R.dimen.elevation_profile_range_handle_touch_outer);
    mTouchInner = res.getDimension(R.dimen.elevation_profile_range_handle_touch_inner);

    final int color = ContextCompat.getColor(context, R.color.track_edit_handle);
    mHandlePaint.setColor(color);
    mHandlePaint.setStrokeWidth(LINE_WIDTH_DP * mDensity);
    mGripMarkPaint.setColor(ContextCompat.getColor(context, R.color.white_primary));
    mGripMarkPaint.setStrokeWidth(GRIP_LINE_DP * mDensity);
    mGripMarkPaint.setStrokeCap(Paint.Cap.ROUND);
    mLabelPaint.setTextSize(LABEL_TEXT_SP * res.getDisplayMetrics().scaledDensity);
  }

  public void setRangeListener(@Nullable RangeListener listener)
  {
    mRangeListener = listener;
  }

  /** Shows the range handles at the entries closest to the given distances. */
  public void setRange(float startDist, float endDist)
  {
    final Entry start = findEntry(startDist);
    final Entry end = findEntry(endDist);
    if (start == null || end == null || start.getX() >= end.getX())
    {
      clearRange();
      return;
    }
    mRangeStart = start;
    mRangeEnd = end;
    invalidate();
  }

  public void clearRange()
  {
    mRangeStart = null;
    mRangeEnd = null;
    mDragged = null;
    invalidate();
  }

  @Nullable
  private Entry findEntry(float dist)
  {
    final LineData data = getData();
    if (data == null || data.getDataSetCount() == 0)
      return null;
    final ILineDataSet set = data.getDataSetByIndex(0);
    return set.getEntryForXValue(dist, Float.NaN, DataSet.Rounding.CLOSEST);
  }

  private float pixelX(float dist)
  {
    final MPPointD p = getTransformer(YAxis.AxisDependency.LEFT).getPixelForValues(dist, 0);
    final float x = (float) p.x;
    MPPointD.recycleInstance(p);
    return x;
  }

  private float valueX(float pixelX)
  {
    final MPPointD p = getValuesByTouchPoint(pixelX, 0, YAxis.AxisDependency.LEFT);
    final float x = (float) p.x;
    MPPointD.recycleInstance(p);
    return x;
  }

  private RectF gripRect(boolean isStart, float lineX)
  {
    final float top = mViewPortHandler.contentTop() + GRIP_TOP_MARGIN_DP * mDensity;
    // The grips hang outwards so that the two handles never cover each other and read as "[ ... ]".
    if (isStart)
      mTmpRect.set(lineX - mGripWidth, top, lineX + LINE_WIDTH_DP * mDensity / 2, top + mGripHeight);
    else
      mTmpRect.set(lineX - LINE_WIDTH_DP * mDensity / 2, top, lineX + mGripWidth, top + mGripHeight);
    return mTmpRect;
  }

  @Override
  protected void onDraw(Canvas canvas)
  {
    super.onDraw(canvas);
    if (mRangeStart == null || mRangeEnd == null)
      return;
    drawHandle(canvas, mRangeStart, true);
    drawHandle(canvas, mRangeEnd, false);
  }

  private void drawHandle(@NonNull Canvas canvas, @NonNull Entry entry, boolean isStart)
  {
    final float x = pixelX(entry.getX());
    final float left = mViewPortHandler.contentLeft();
    final float right = mViewPortHandler.contentRight();
    // Scrolled out of the visible part of a zoomed chart.
    if (x < left - mGripWidth || x > right + mGripWidth)
      return;

    canvas.drawLine(x, mViewPortHandler.contentTop(), x, mViewPortHandler.contentBottom(), mHandlePaint);

    final float corner = GRIP_CORNER_DP * mDensity;
    final RectF grip = gripRect(isStart, x);
    canvas.drawRoundRect(grip, corner, corner, mHandlePaint);

    // Two grooves in the middle of the grip.
    final float cx = grip.centerX();
    final float cy = grip.centerY();
    final float dx = 2.5f * mDensity;
    final float half = GRIP_LINE_HEIGHT_DP * mDensity / 2;
    canvas.drawLine(cx - dx, cy - half, cx - dx, cy + half, mGripMarkPaint);
    canvas.drawLine(cx + dx, cy - half, cx + dx, cy + half, mGripMarkPaint);

    if (mDragged == (isStart ? Handle.START : Handle.END))
      drawDistanceLabel(canvas, entry.getX(), x, isStart);
  }

  // The label is placed on the inner side of the line, away from the finger and the grip.
  private void drawDistanceLabel(@NonNull Canvas canvas, float dist, float lineX, boolean isStart)
  {
    final String text = StringUtils.nativeFormatDistance(dist).toString(getContext());
    final float pad = LABEL_PADDING_DP * mDensity;
    final float textWidth = mLabelPaint.measureText(text);
    final Paint.FontMetrics fm = mLabelPaint.getFontMetrics();
    final float height = fm.descent - fm.ascent + pad;
    final float width = textWidth + 2 * pad;
    final float top = mViewPortHandler.contentTop() + GRIP_TOP_MARGIN_DP * mDensity;
    final float gap = 4 * mDensity;
    final float left = isStart ? lineX + gap : lineX - gap - width;
    mTmpRect.set(left, top, left + width, top + height);
    canvas.drawRoundRect(mTmpRect, pad, pad, mHandlePaint);
    mLabelPaint.setColor(mGripMarkPaint.getColor());
    canvas.drawText(text, left + pad, top + pad / 2 - fm.ascent, mLabelPaint);
  }

  @Override
  protected void init()
  {
    super.init();
    mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev)
  {
    getParent().requestDisallowInterceptTouchEvent(true);
    return super.onInterceptTouchEvent(ev);
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    if (!mTouchEnabled)
      return super.onTouchEvent(event);

    if (handleRangeTouch(event))
      return true;

    final int action = event.getActionMasked();

    if (action == MotionEvent.ACTION_DOWN)
    {
      mLastHighlightedX = Float.NaN;
      mMarkerScreenX = getCurrentHighlightScreenX();
      mIsSelecting = !isZoomedIn() || isTouchNearHighlight(event.getX(), mMarkerScreenX);
      // When zoomed, highlight immediately. When not zoomed, wait for finger
      // to move past touchSlop to distinguish drag from pinch-zoom start.
      mSelectConfirmed = isZoomedIn();
      mTouchStartX = event.getX();
    }

    // Second finger → zoom, stop selecting.
    if (action == MotionEvent.ACTION_POINTER_DOWN)
    {
      mIsSelecting = false;
      // Preserve current marker position if the user already dragged it.
      if (mSelectConfirmed || isZoomedIn())
        mMarkerScreenX = getCurrentHighlightScreenX();
      mSelectConfirmed = false;
      if (hasHighlight())
        performHighlightAtScreenX(mMarkerScreenX);
    }

    // SELECT: marker follows finger on drag; taps delegated to super's onSingleTapUp.
    if (mIsSelecting)
    {
      if (action == MotionEvent.ACTION_MOVE)
      {
        if (!mSelectConfirmed && Math.abs(event.getX() - mTouchStartX) > mTouchSlop)
          mSelectConfirmed = true;
        if (mSelectConfirmed)
          performHighlightAtScreenX(event.getX());
      }
      if (isZoomedIn())
      {
        // Super doesn't see events when zoomed, so handle taps ourselves.
        if (action == MotionEvent.ACTION_UP)
          performHighlightAtScreenX(event.getX());
        return true;
      }
      // Reset mLastHighlighted so super's onSingleTapUp → performHighlight
      // doesn't toggle OFF the highlight we're about to set.
      if (action == MotionEvent.ACTION_UP)
        mChartTouchListener.setLastHighlighted(null);
      return super.onTouchEvent(event);
    }

    // PAN / ZOOM: chart moves, marker stays at fixed screen-X.
    boolean result = super.onTouchEvent(event);
    if (action == MotionEvent.ACTION_MOVE && hasHighlight())
      performHighlightAtScreenX(mMarkerScreenX);
    return result;
  }

  // Dragging a range handle takes precedence over the marker selection and the pan/zoom handling.
  private boolean handleRangeTouch(MotionEvent ev)
  {
    if (mRangeStart == null || mRangeEnd == null)
      return false;

    switch (ev.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN:
      if (grabHandle(ev.getX()))
      {
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
      return false;
    case MotionEvent.ACTION_MOVE:
      if (mDragged != null)
      {
        dragHandle(ev.getX());
        return true;
      }
      return false;
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_CANCEL:
      if (mDragged != null)
      {
        mDragged = null;
        invalidate();
        if (mRangeListener != null)
          mRangeListener.onRangeDragFinished(mRangeStart, mRangeEnd);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP)
          performClick();
        return true;
      }
      return false;
    default:
      return mDragged != null; // Ignore secondary pointers while dragging.
    }
  }

  @Override
  public boolean performClick()
  {
    return super.performClick();
  }

  private boolean grabHandle(float touchX)
  {
    final float startX = pixelX(mRangeStart.getX());
    final float endX = pixelX(mRangeEnd.getX());
    // Each handle is easiest to grab from its grip side, which also disambiguates handles that are close together.
    final boolean nearStart = touchX >= startX - mTouchOuter && touchX <= startX + mTouchInner;
    final boolean nearEnd = touchX >= endX - mTouchInner && touchX <= endX + mTouchOuter;
    if (!nearStart && !nearEnd)
      return false;

    final boolean pickStart =
        nearStart
        && (!nearEnd || Math.abs(touchX - (startX - mGripWidth / 2)) <= Math.abs(touchX - (endX + mGripWidth / 2)));
    mDragged = pickStart ? Handle.START : Handle.END;
    mGrabOffsetPx = (pickStart ? startX : endX) - touchX;
    invalidate();
    return true;
  }

  private void dragHandle(float touchX)
  {
    final float contentLeft = mViewPortHandler.contentLeft();
    final float contentRight = mViewPortHandler.contentRight();
    final float x = Math.max(contentLeft, Math.min(contentRight, touchX + mGrabOffsetPx));
    final Entry entry = findEntry(valueX(x));
    if (entry == null)
      return;

    // The handles must not cross or coincide.
    if (mDragged == Handle.START)
    {
      if (entry.getX() >= mRangeEnd.getX() || entry == mRangeStart)
        return;
      mRangeStart = entry;
    }
    else
    {
      if (entry.getX() <= mRangeStart.getX() || entry == mRangeEnd)
        return;
      mRangeEnd = entry;
    }

    invalidate();
    if (mRangeListener != null)
      mRangeListener.onRangeDragged(mRangeStart, mRangeEnd, entry);
  }

  @Override
  public void computeScroll()
  {
    float prevLowestX = getLowestVisibleX();
    super.computeScroll();
    // During deceleration (fling) the viewport changes without touch events.
    // Keep the marker at its fixed screen position.
    if (!mIsSelecting && getLowestVisibleX() != prevLowestX && hasHighlight())
      performHighlightAtScreenX(mMarkerScreenX);
  }

  private boolean hasHighlight()
  {
    final Highlight[] h = getHighlighted();
    return h != null && h.length > 0;
  }

  private boolean isZoomedIn()
  {
    // Threshold above 1.0 to ignore floating-point jitter around the default scale.
    return getViewPortHandler().getScaleX() > 1.01f;
  }

  private float getCurrentHighlightScreenX()
  {
    final Highlight[] highlighted = getHighlighted();
    if (highlighted == null || highlighted.length == 0)
      return getWidth() / 2f;

    // The user-selected highlight is always last: ChartController.selectAtDistance()
    // passes [curPos, h] in that order; RouteElevationChartController uses a single highlight.
    final Highlight last = highlighted[highlighted.length - 1];
    final MPPointD pix = getTransformer(YAxis.AxisDependency.LEFT).getPixelForValues(last.getX(), last.getY());
    final float result = (float) pix.x;
    MPPointD.recycleInstance(pix);
    return result;
  }

  private boolean isTouchNearHighlight(float touchX, float highlightScreenX)
  {
    if (!hasHighlight())
      return false;

    final float captureRadiusPx = CAPTURE_RADIUS_DP * getResources().getDisplayMetrics().density;
    return Math.abs(touchX - highlightScreenX) <= captureRadiusPx;
  }

  private void performHighlightAtScreenX(float screenX)
  {
    if (getHighlighter() == null)
      return;
    // Cheap pre-check: convert screen → data X without allocating a Highlight.
    final MPPointD pos = getTransformer(YAxis.AxisDependency.LEFT).getValuesByTouchPoint(screenX, 0);
    final float dataX = (float) pos.x;
    MPPointD.recycleInstance(pos);
    if (dataX == mLastHighlightedX)
      return;
    final Highlight h = getHighlighter().getHighlight(screenX, 0);
    if (h != null)
    {
      mLastHighlightedX = h.getX();
      highlightValue(h, true);
    }
  }
}
