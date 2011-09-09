/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.DynamicLayout;
import android.text.Layout.Alignment;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;

import com.android.settings.R;
import com.google.common.base.Preconditions;

/**
 * Sweep across a {@link ChartView} at a specific {@link ChartAxis} value, which
 * a user can drag.
 */
public class ChartSweepView extends View {

    private static final boolean DRAW_OUTLINE = false;

    // TODO: clean up all the various padding/offset/margins

    private Drawable mSweep;
    private Rect mSweepPadding = new Rect();

    /** Offset of content inside this view. */
    private Rect mContentOffset = new Rect();
    /** Offset of {@link #mSweep} inside this view. */
    private Point mSweepOffset = new Point();

    private Rect mMargins = new Rect();
    private float mNeighborMargin;

    private int mFollowAxis;

    private int mLabelSize;
    private int mLabelTemplateRes;
    private int mLabelColor;

    private SpannableStringBuilder mLabelTemplate;
    private DynamicLayout mLabelLayout;

    private ChartAxis mAxis;
    private long mValue;
    private long mLabelValue;

    private long mValidAfter;
    private long mValidBefore;
    private ChartSweepView mValidAfterDynamic;
    private ChartSweepView mValidBeforeDynamic;

    private Paint mOutlinePaint = new Paint();

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public interface OnSweepListener {
        public void onSweep(ChartSweepView sweep, boolean sweepDone);
    }

    private OnSweepListener mListener;
    private MotionEvent mTracking;

    public ChartSweepView(Context context) {
        this(context, null);
    }

    public ChartSweepView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChartSweepView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ChartSweepView, defStyle, 0);

        setSweepDrawable(a.getDrawable(R.styleable.ChartSweepView_sweepDrawable));
        setFollowAxis(a.getInt(R.styleable.ChartSweepView_followAxis, -1));
        setNeighborMargin(a.getDimensionPixelSize(R.styleable.ChartSweepView_neighborMargin, 0));

        setLabelSize(a.getDimensionPixelSize(R.styleable.ChartSweepView_labelSize, 0));
        setLabelTemplate(a.getResourceId(R.styleable.ChartSweepView_labelTemplate, 0));
        setLabelColor(a.getColor(R.styleable.ChartSweepView_labelColor, Color.BLUE));

        mOutlinePaint.setColor(Color.RED);
        mOutlinePaint.setStrokeWidth(1f);
        mOutlinePaint.setStyle(Style.STROKE);

        a.recycle();

        setWillNotDraw(false);
    }

    void init(ChartAxis axis) {
        mAxis = Preconditions.checkNotNull(axis, "missing axis");
    }

    public int getFollowAxis() {
        return mFollowAxis;
    }

    public Rect getMargins() {
        return mMargins;
    }

    /**
     * Return the number of pixels that the "target" area is inset from the
     * {@link View} edge, along the current {@link #setFollowAxis(int)}.
     */
    private float getTargetInset() {
        if (mFollowAxis == VERTICAL) {
            final float targetHeight = mSweep.getIntrinsicHeight() - mSweepPadding.top
                    - mSweepPadding.bottom;
            return mSweepPadding.top + (targetHeight / 2) + mSweepOffset.y;
        } else {
            final float targetWidth = mSweep.getIntrinsicWidth() - mSweepPadding.left
                    - mSweepPadding.right;
            return mSweepPadding.left + (targetWidth / 2) + mSweepOffset.x;
        }
    }

    public void addOnSweepListener(OnSweepListener listener) {
        mListener = listener;
    }

    private void dispatchOnSweep(boolean sweepDone) {
        if (mListener != null) {
            mListener.onSweep(this, sweepDone);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        requestLayout();
    }

    public void setSweepDrawable(Drawable sweep) {
        if (mSweep != null) {
            mSweep.setCallback(null);
            unscheduleDrawable(mSweep);
        }

        if (sweep != null) {
            sweep.setCallback(this);
            if (sweep.isStateful()) {
                sweep.setState(getDrawableState());
            }
            sweep.setVisible(getVisibility() == VISIBLE, false);
            mSweep = sweep;
            sweep.getPadding(mSweepPadding);
        } else {
            mSweep = null;
        }

        invalidate();
    }

    public void setFollowAxis(int followAxis) {
        mFollowAxis = followAxis;
    }

    public void setLabelSize(int size) {
        mLabelSize = size;
        invalidateLabelTemplate();
    }

    public void setLabelTemplate(int resId) {
        mLabelTemplateRes = resId;
        invalidateLabelTemplate();
    }

    public void setLabelColor(int color) {
        mLabelColor = color;
        invalidateLabelTemplate();
    }

    private void invalidateLabelTemplate() {
        if (mLabelTemplateRes != 0) {
            final CharSequence template = getResources().getText(mLabelTemplateRes);

            final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.density = getResources().getDisplayMetrics().density;
            paint.setCompatibilityScaling(getResources().getCompatibilityInfo().applicationScale);
            paint.setColor(mLabelColor);
            paint.setShadowLayer(4 * paint.density, 0, 0, Color.BLACK);

            mLabelTemplate = new SpannableStringBuilder(template);
            mLabelLayout = new DynamicLayout(
                    mLabelTemplate, paint, mLabelSize, Alignment.ALIGN_RIGHT, 1f, 0f, false);
            invalidateLabel();

        } else {
            mLabelTemplate = null;
            mLabelLayout = null;
        }

        invalidate();
        requestLayout();
    }

    private void invalidateLabel() {
        if (mLabelTemplate != null && mAxis != null) {
            mLabelValue = mAxis.buildLabel(getResources(), mLabelTemplate, mValue);
            invalidate();
        } else {
            mLabelValue = mValue;
        }
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mSweep != null) {
            mSweep.jumpToCurrentState();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (mSweep != null) {
            mSweep.setVisible(visibility == VISIBLE, false);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mSweep || super.verifyDrawable(who);
    }

    public ChartAxis getAxis() {
        return mAxis;
    }

    public void setValue(long value) {
        mValue = value;
        invalidateLabel();
    }

    public long getValue() {
        return mValue;
    }

    public long getLabelValue() {
        return mLabelValue;
    }

    public float getPoint() {
        if (isEnabled()) {
            return mAxis.convertToPoint(mValue);
        } else {
            // when disabled, show along top edge
            return 0;
        }
    }

    /**
     * Set valid range this sweep can move within, in {@link #mAxis} values. The
     * most restrictive combination of all valid ranges is used.
     */
    public void setValidRange(long validAfter, long validBefore) {
        mValidAfter = validAfter;
        mValidBefore = validBefore;
    }

    public void setNeighborMargin(float neighborMargin) {
        mNeighborMargin = neighborMargin;
    }

    /**
     * Set valid range this sweep can move within, defined by the given
     * {@link ChartSweepView}. The most restrictive combination of all valid
     * ranges is used.
     */
    public void setValidRangeDynamic(ChartSweepView validAfter, ChartSweepView validBefore) {
        mValidAfterDynamic = validAfter;
        mValidBeforeDynamic = validBefore;
    }

    /**
     * Test if given {@link MotionEvent} is closer to another
     * {@link ChartSweepView} compared to ourselves.
     */
    public boolean isTouchCloserTo(MotionEvent eventInParent, ChartSweepView another) {
        if (another == null) return false;

        if (mFollowAxis == HORIZONTAL) {
            final float selfDist = Math.abs(eventInParent.getX() - (getX() + getTargetInset()));
            final float anotherDist = Math.abs(
                    eventInParent.getX() - (another.getX() + another.getTargetInset()));
            return anotherDist < selfDist;
        } else {
            final float selfDist = Math.abs(eventInParent.getY() - (getY() + getTargetInset()));
            final float anotherDist = Math.abs(
                    eventInParent.getY() - (another.getY() + another.getTargetInset()));
            return anotherDist < selfDist;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        final View parent = (View) getParent();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {

                // only start tracking when in sweet spot
                final boolean accept;
                if (mFollowAxis == VERTICAL) {
                    accept = event.getX() > getWidth() - (mSweepPadding.right * 8);
                } else {
                    accept = event.getY() > getHeight() - (mSweepPadding.bottom * 8);
                }

                final MotionEvent eventInParent = event.copy();
                eventInParent.offsetLocation(getLeft(), getTop());

                // ignore event when closer to a neighbor
                if (isTouchCloserTo(eventInParent, mValidAfterDynamic)
                        || isTouchCloserTo(eventInParent, mValidBeforeDynamic)) {
                    return false;
                }

                if (accept) {
                    mTracking = event.copy();

                    // starting drag should activate entire chart
                    if (!parent.isActivated()) {
                        parent.setActivated(true);
                    }

                    return true;
                } else {
                    return false;
                }
            }
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);

                // content area of parent
                final Rect parentContent = getParentContentRect();
                final Rect clampRect = computeClampRect(parentContent);

                if (mFollowAxis == VERTICAL) {
                    final float currentTargetY = getTop() - mMargins.top;
                    final float requestedTargetY = currentTargetY
                            + (event.getRawY() - mTracking.getRawY());
                    final float clampedTargetY = MathUtils.constrain(
                            requestedTargetY, clampRect.top, clampRect.bottom);
                    setTranslationY(clampedTargetY - currentTargetY);

                    setValue(mAxis.convertToValue(clampedTargetY - parentContent.top));
                } else {
                    final float currentTargetX = getLeft() - mMargins.left;
                    final float requestedTargetX = currentTargetX
                            + (event.getRawX() - mTracking.getRawX());
                    final float clampedTargetX = MathUtils.constrain(
                            requestedTargetX, clampRect.left, clampRect.right);
                    setTranslationX(clampedTargetX - currentTargetX);

                    setValue(mAxis.convertToValue(clampedTargetX - parentContent.left));
                }

                dispatchOnSweep(false);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                mTracking = null;
                mValue = mLabelValue;
                dispatchOnSweep(true);
                setTranslationX(0);
                setTranslationY(0);
                requestLayout();
                return true;
            }
            default: {
                return false;
            }
        }
    }

    /**
     * Update {@link #mValue} based on current position, including any
     * {@link #onTouchEvent(MotionEvent)} in progress. Typically used when
     * {@link ChartAxis} changes during sweep adjustment.
     */
    public void updateValueFromPosition() {
        final Rect parentContent = getParentContentRect();
        if (mFollowAxis == VERTICAL) {
            final float effectiveY = getY() - mMargins.top - parentContent.top;
            setValue(mAxis.convertToValue(effectiveY));
        } else {
            final float effectiveX = getX() - mMargins.left - parentContent.left;
            setValue(mAxis.convertToValue(effectiveX));
        }
    }

    public int shouldAdjustAxis() {
        return mAxis.shouldAdjustAxis(getValue());
    }

    private Rect getParentContentRect() {
        final View parent = (View) getParent();
        return new Rect(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getWidth() - parent.getPaddingRight(),
                parent.getHeight() - parent.getPaddingBottom());
    }

    @Override
    public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {
        // ignored to keep LayoutTransition from animating us
    }

    @Override
    public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {
        // ignored to keep LayoutTransition from animating us
    }

    private long getValidAfterDynamic() {
        final ChartSweepView dynamic = mValidAfterDynamic;
        return dynamic != null && dynamic.isEnabled() ? dynamic.getValue() : Long.MIN_VALUE;
    }

    private long getValidBeforeDynamic() {
        final ChartSweepView dynamic = mValidBeforeDynamic;
        return dynamic != null && dynamic.isEnabled() ? dynamic.getValue() : Long.MAX_VALUE;
    }

    /**
     * Compute {@link Rect} in {@link #getParent()} coordinates that we should
     * be clamped inside of, usually from {@link #setValidRange(long, long)}
     * style rules.
     */
    private Rect computeClampRect(Rect parentContent) {
        // create two rectangles, and pick most restrictive combination
        final Rect rect = buildClampRect(parentContent, mValidAfter, mValidBefore, 0f);
        final Rect dynamicRect = buildClampRect(
                parentContent, getValidAfterDynamic(), getValidBeforeDynamic(), mNeighborMargin);

        rect.intersect(dynamicRect);
        return rect;
    }

    private Rect buildClampRect(
            Rect parentContent, long afterValue, long beforeValue, float margin) {
        if (mAxis instanceof InvertedChartAxis) {
            long temp = beforeValue;
            beforeValue = afterValue;
            afterValue = temp;
        }

        final boolean afterValid = afterValue != Long.MIN_VALUE && afterValue != Long.MAX_VALUE;
        final boolean beforeValid = beforeValue != Long.MIN_VALUE && beforeValue != Long.MAX_VALUE;

        final float afterPoint = mAxis.convertToPoint(afterValue) + margin;
        final float beforePoint = mAxis.convertToPoint(beforeValue) - margin;

        final Rect clampRect = new Rect(parentContent);
        if (mFollowAxis == VERTICAL) {
            if (beforeValid) clampRect.bottom = clampRect.top + (int) beforePoint;
            if (afterValid) clampRect.top += afterPoint;
        } else {
            if (beforeValid) clampRect.right = clampRect.left + (int) beforePoint;
            if (afterValid) clampRect.left += afterPoint;
        }
        return clampRect;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mSweep.isStateful()) {
            mSweep.setState(getDrawableState());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        // TODO: handle vertical labels
        if (isEnabled() && mLabelLayout != null) {
            final int sweepHeight = mSweep.getIntrinsicHeight();
            final int templateHeight = mLabelLayout.getHeight();

            mSweepOffset.x = 0;
            mSweepOffset.y = 0;
            mSweepOffset.y = (int) ((templateHeight / 2) - getTargetInset());
            setMeasuredDimension(mSweep.getIntrinsicWidth(), Math.max(sweepHeight, templateHeight));

        } else {
            mSweepOffset.x = 0;
            mSweepOffset.y = 0;
            setMeasuredDimension(mSweep.getIntrinsicWidth(), mSweep.getIntrinsicHeight());
        }

        if (mFollowAxis == VERTICAL) {
            final int targetHeight = mSweep.getIntrinsicHeight() - mSweepPadding.top
                    - mSweepPadding.bottom;
            mMargins.top = -(mSweepPadding.top + (targetHeight / 2));
            mMargins.bottom = 0;
            mMargins.left = -mSweepPadding.left;
            mMargins.right = mSweepPadding.right;
        } else {
            final int targetWidth = mSweep.getIntrinsicWidth() - mSweepPadding.left
                    - mSweepPadding.right;
            mMargins.left = -(mSweepPadding.left + (targetWidth / 2));
            mMargins.right = 0;
            mMargins.top = -mSweepPadding.top;
            mMargins.bottom = mSweepPadding.bottom;
        }

        mContentOffset.set(0, 0, 0, 0);

        // make touch target area larger
        final int widthBefore = getMeasuredWidth();
        final int heightBefore = getMeasuredHeight();
        if (mFollowAxis == HORIZONTAL) {
            final int widthAfter = widthBefore * 3;
            setMeasuredDimension(widthAfter, heightBefore);
            mContentOffset.left = (widthAfter - widthBefore) / 2;

            final int offset = mSweepPadding.bottom * 2;
            mContentOffset.bottom -= offset;
            mMargins.bottom += offset;
        } else {
            final int heightAfter = heightBefore * 3;
            setMeasuredDimension(widthBefore, heightAfter);
            mContentOffset.offset(0, (heightAfter - heightBefore) / 2);

            final int offset = mSweepPadding.right * 2;
            mContentOffset.right -= offset;
            mMargins.right += offset;
        }

        mSweepOffset.offset(mContentOffset.left, mContentOffset.top);
        mMargins.offset(-mSweepOffset.x, -mSweepOffset.y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();

        if (DRAW_OUTLINE) {
            canvas.drawRect(0, 0, width, height, mOutlinePaint);
        }

        // when overlapping with neighbor, split difference and push label
        float margin;
        float labelOffset = 0;
        if (mFollowAxis == VERTICAL) {
            if (mValidAfterDynamic != null) {
                margin = getLabelTop(mValidAfterDynamic) - getLabelBottom(this);
                if (margin < 0) {
                    labelOffset = margin / 2;
                }
            } else if (mValidBeforeDynamic != null) {
                margin = getLabelTop(this) - getLabelBottom(mValidBeforeDynamic);
                if (margin < 0) {
                    labelOffset = -margin / 2;
                }
            }
        } else {
            // TODO: implement horizontal labels
        }

        // when offsetting label, neighbor probably needs to offset too
        if (labelOffset != 0) {
            if (mValidAfterDynamic != null) mValidAfterDynamic.invalidate();
            if (mValidBeforeDynamic != null) mValidBeforeDynamic.invalidate();
        }

        final int labelSize;
        if (isEnabled() && mLabelLayout != null) {
            final int count = canvas.save();
            {
                canvas.translate(mContentOffset.left, mContentOffset.top + labelOffset);
                mLabelLayout.draw(canvas);
            }
            canvas.restoreToCount(count);
            labelSize = mLabelSize;
        } else {
            labelSize = 0;
        }

        if (mFollowAxis == VERTICAL) {
            mSweep.setBounds(labelSize, mSweepOffset.y, width + mContentOffset.right,
                    mSweepOffset.y + mSweep.getIntrinsicHeight());
        } else {
            mSweep.setBounds(mSweepOffset.x, labelSize, mSweepOffset.x + mSweep.getIntrinsicWidth(),
                    height + mContentOffset.bottom);
        }

        mSweep.draw(canvas);
    }

    public static float getLabelTop(ChartSweepView view) {
        return view.getY() + view.mContentOffset.top;
    }

    public static float getLabelBottom(ChartSweepView view) {
        return getLabelTop(view) + view.mLabelLayout.getHeight();
    }
}
