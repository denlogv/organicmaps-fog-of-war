package app.organicmaps.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorPickerView extends View
{
  private static final float HUE_BAR_HEIGHT_DP = 36f;
  private static final float SV_MIN_HEIGHT_DP = 160f;
  private static final float PREVIEW_SIZE_DP = 32f;
  private static final float GAP_DP = 16f;
  private static final float THUMB_RADIUS_DP = 10f;
  private static final float THUMB_STROKE_DP = 2.5f;
  private static final float CORNER_RADIUS_DP = 6f;

  private final float[] mHsv = {0f, 0f, 0f};

  private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mThumbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mThumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final RectF mHueBarRect = new RectF();
  private final RectF mSvPanelRect = new RectF();

  private float mDensity;
  private float mHueBarHeight;
  private float mPreviewSize;
  private float mGap;
  private float mThumbRadius;
  private float mCornerRadius;

  private LinearGradient mHueShader;

  private boolean mTrackingHue;
  private boolean mTrackingSv;

  private OnColorChangedListener mListener;

  public interface OnColorChangedListener
  {
    void onColorChanged(int color);
  }

  public ColorPickerView(Context context)
  {
    super(context);
    init();
  }

  public ColorPickerView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    init();
  }

  private void init()
  {
    mDensity = getResources().getDisplayMetrics().density;
    mHueBarHeight = HUE_BAR_HEIGHT_DP * mDensity;
    mPreviewSize = PREVIEW_SIZE_DP * mDensity;
    mGap = GAP_DP * mDensity;
    mThumbRadius = THUMB_RADIUS_DP * mDensity;
    mCornerRadius = CORNER_RADIUS_DP * mDensity;

    mThumbFillPaint.setStyle(Paint.Style.FILL);

    mThumbStrokePaint.setStyle(Paint.Style.STROKE);
    mThumbStrokePaint.setStrokeWidth(THUMB_STROKE_DP * mDensity);
    mThumbStrokePaint.setColor(Color.WHITE);

    mBorderPaint.setStyle(Paint.Style.STROKE);
    mBorderPaint.setStrokeWidth(mDensity);
    mBorderPaint.setColor(0x30000000);

    mTextPaint.setTextSize(14f * mDensity);
    mTextPaint.setColor(0xFF888888);

    mShadowPaint.setStyle(Paint.Style.FILL);
    mShadowPaint.setColor(0x40000000);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    float contentWidth = width - getPaddingLeft() - getPaddingRight();
    float svHeight = Math.max(SV_MIN_HEIGHT_DP * mDensity, contentWidth * 0.55f);
    int height = (int) (getPaddingTop() + mHueBarHeight + mGap + svHeight + mGap + mPreviewSize + getPaddingBottom());
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh)
  {
    float left = getPaddingLeft();
    float right = w - getPaddingRight();
    float top = getPaddingTop();

    mHueBarRect.set(left, top, right, top + mHueBarHeight);

    float svTop = mHueBarRect.bottom + mGap;
    float svBottom = h - getPaddingBottom() - mPreviewSize - mGap;
    mSvPanelRect.set(left, svTop, right, Math.max(svTop + mDensity, svBottom));

    buildHueShader();
  }

  private void buildHueShader()
  {
    int[] colors = new int[7];
    float[] hsv = {0f, 1f, 1f};
    for (int i = 0; i < 7; i++)
    {
      hsv[0] = i * 60f;
      colors[i] = Color.HSVToColor(hsv);
    }
    mHueShader = new LinearGradient(mHueBarRect.left, 0, mHueBarRect.right, 0, colors, null, Shader.TileMode.CLAMP);
  }

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (mHueBarRect.width() <= 0)
      return;

    drawHueBar(canvas);
    drawSvPanel(canvas);
    drawPreview(canvas);
  }

  private void drawHueBar(Canvas canvas)
  {
    mPaint.setShader(mHueShader);
    canvas.drawRoundRect(mHueBarRect, mCornerRadius, mCornerRadius, mPaint);
    mPaint.setShader(null);
    canvas.drawRoundRect(mHueBarRect, mCornerRadius, mCornerRadius, mBorderPaint);

    float x = mHueBarRect.left + (mHsv[0] / 360f) * mHueBarRect.width();
    float y = mHueBarRect.centerY();
    float[] pureHsv = {mHsv[0], 1f, 1f};
    drawThumb(canvas, x, y, Color.HSVToColor(pureHsv), false);
  }

  private void drawSvPanel(Canvas canvas)
  {
    float[] pureHsv = {mHsv[0], 1f, 1f};
    int pureHue = Color.HSVToColor(pureHsv);

    LinearGradient satShader =
        new LinearGradient(mSvPanelRect.left, 0, mSvPanelRect.right, 0, Color.WHITE, pureHue, Shader.TileMode.CLAMP);
    mPaint.setShader(satShader);
    canvas.drawRoundRect(mSvPanelRect, mCornerRadius, mCornerRadius, mPaint);

    LinearGradient valShader =
        new LinearGradient(0, mSvPanelRect.top, 0, mSvPanelRect.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
    mPaint.setShader(valShader);
    canvas.drawRoundRect(mSvPanelRect, mCornerRadius, mCornerRadius, mPaint);
    mPaint.setShader(null);

    canvas.drawRoundRect(mSvPanelRect, mCornerRadius, mCornerRadius, mBorderPaint);

    float x = mSvPanelRect.left + mHsv[1] * mSvPanelRect.width();
    float y = mSvPanelRect.top + (1f - mHsv[2]) * mSvPanelRect.height();
    drawThumb(canvas, x, y, getColor(), true);
  }

  private void drawThumb(Canvas canvas, float cx, float cy, int fillColor, boolean adaptiveStroke)
  {
    // Drop shadow
    canvas.drawCircle(cx, cy + mDensity, mThumbRadius + mDensity, mShadowPaint);
    // Fill
    mThumbFillPaint.setColor(fillColor);
    canvas.drawCircle(cx, cy, mThumbRadius, mThumbFillPaint);
    // Stroke — dark on light colors, white on dark colors
    if (adaptiveStroke)
    {
      float lum =
          (0.299f * Color.red(fillColor) + 0.587f * Color.green(fillColor) + 0.114f * Color.blue(fillColor)) / 255f;
      mThumbStrokePaint.setColor(lum > 0.5f ? 0xFF333333 : Color.WHITE);
    }
    else
    {
      mThumbStrokePaint.setColor(Color.WHITE);
    }
    canvas.drawCircle(cx, cy, mThumbRadius, mThumbStrokePaint);
  }

  private void drawPreview(Canvas canvas)
  {
    float left = getPaddingLeft();
    float top = mSvPanelRect.bottom + mGap;
    RectF rect = new RectF(left, top, left + mPreviewSize, top + mPreviewSize);

    int color = getColor();
    mPaint.setColor(color);
    canvas.drawRoundRect(rect, mCornerRadius, mCornerRadius, mPaint);
    canvas.drawRoundRect(rect, mCornerRadius, mCornerRadius, mBorderPaint);

    String hex = String.format("#%06X", color & 0xFFFFFF);
    float textX = rect.right + mGap;
    float textY = rect.centerY() - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
    canvas.drawText(hex, textX, textY, mTextPaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    float x = event.getX();
    float y = event.getY();

    switch (event.getAction())
    {
    case MotionEvent.ACTION_DOWN:
      if (hitTest(mHueBarRect, x, y))
      {
        mTrackingHue = true;
        if (getParent() != null)
          getParent().requestDisallowInterceptTouchEvent(true);
        updateHue(x);
        return true;
      }
      if (hitTest(mSvPanelRect, x, y))
      {
        mTrackingSv = true;
        if (getParent() != null)
          getParent().requestDisallowInterceptTouchEvent(true);
        updateSv(x, y);
        return true;
      }
      break;
    case MotionEvent.ACTION_MOVE:
      if (mTrackingHue)
      {
        updateHue(x);
        return true;
      }
      if (mTrackingSv)
      {
        updateSv(x, y);
        return true;
      }
      break;
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_CANCEL:
      mTrackingHue = false;
      mTrackingSv = false;
      if (getParent() != null)
        getParent().requestDisallowInterceptTouchEvent(false);
      break;
    }
    return super.onTouchEvent(event);
  }

  private boolean hitTest(RectF rect, float x, float y)
  {
    float r = mThumbRadius;
    return x >= rect.left - r && x <= rect.right + r && y >= rect.top - r && y <= rect.bottom + r;
  }

  private void updateHue(float x)
  {
    float ratio = (x - mHueBarRect.left) / mHueBarRect.width();
    mHsv[0] = clamp(ratio, 0f, 1f) * 360f;
    invalidate();
    notifyChanged();
  }

  private void updateSv(float x, float y)
  {
    mHsv[1] = clamp((x - mSvPanelRect.left) / mSvPanelRect.width(), 0f, 1f);
    mHsv[2] = clamp(1f - (y - mSvPanelRect.top) / mSvPanelRect.height(), 0f, 1f);
    invalidate();
    notifyChanged();
  }

  private static float clamp(float v, float min, float max)
  {
    return Math.max(min, Math.min(max, v));
  }

  private void notifyChanged()
  {
    if (mListener != null)
      mListener.onColorChanged(getColor());
  }

  public int getColor()
  {
    return Color.HSVToColor(mHsv);
  }

  public void setColor(int color)
  {
    Color.colorToHSV(color | 0xFF000000, mHsv);
    invalidate();
  }

  public void setOnColorChangedListener(OnColorChangedListener listener)
  {
    mListener = listener;
  }
}
