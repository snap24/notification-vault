package com.zygisk_enc.notivault.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.zygisk_enc.notivault.R;
import java.util.ArrayList;
import java.util.List;

public class AnalyticsDistributionChartView extends View {

    public static class BarItem {
        public String label;
        public String inspectorLabel;
        public int count;
        public boolean isPeak;

        public BarItem(String label, int count, boolean isPeak) {
            this(label, label, count, isPeak);
        }

        public BarItem(String label, String inspectorLabel, int count, boolean isPeak) {
            this.label = label;
            this.inspectorLabel = inspectorLabel;
            this.count = count;
            this.isPeak = isPeak;
        }
    }

    public interface OnBarSelectedListener {
        void onBarSelected(int index, BarItem item);
    }

    private final List<BarItem> items = new ArrayList<>();
    private int selectedIndex = -1;
    private OnBarSelectedListener listener;

    private Paint barPaint;
    private Paint peakPaint;
    private Paint selectedPaint;
    private Paint emptyBarPaint;
    private Paint textPaint;
    private Paint gridPaint;

    private float animationProgress = 1.0f;
    private ValueAnimator animator;

    private int primaryColor;
    private int peakColor;
    private int barNormalColor;
    private int barEmptyColor;
    private int textColor;
    private int gridColor;

    public AnalyticsDistributionChartView(Context context) {
        super(context);
        init(context);
    }

    public AnalyticsDistributionChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AnalyticsDistributionChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        TypedValue tv = new TypedValue();
        
        // Primary
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) {
            primaryColor = tv.data;
        } else {
            primaryColor = 0xFF6750A4;
        }

        // Secondary / Peak
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorTertiary, tv, true)) {
            peakColor = tv.data;
        } else {
            peakColor = 0xFF7D5260;
        }

        // Normal bar (Primary Container)
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, tv, true)) {
            barNormalColor = tv.data;
        } else {
            barNormalColor = 0xFFEADDFF;
        }

        // Empty track (Surface Variant)
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, tv, true)) {
            barEmptyColor = tv.data;
        } else {
            barEmptyColor = 0xFFE7E0EC;
        }

        // Text
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)) {
            textColor = tv.data;
        } else {
            textColor = 0xFF49454F;
        }

        // Grid
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            gridColor = tv.data;
        } else {
            gridColor = 0xFFCAC4D0;
        }

        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setColor(barNormalColor);

        peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        peakPaint.setColor(peakColor);

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setColor(primaryColor);

        emptyBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emptyBarPaint.setColor(barEmptyColor);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(dpToPx(10));
        textPaint.setTextAlign(Paint.Align.CENTER);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(dpToPx(1));
    }

    public void setData(List<BarItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        this.selectedIndex = -1;

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(400);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            animationProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setOnBarSelectedListener(OnBarSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (items.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();

        float paddingLeft = dpToPx(8);
        float paddingRight = dpToPx(8);
        float paddingTop = dpToPx(16);
        float paddingBottom = dpToPx(24);

        float chartWidth = width - paddingLeft - paddingRight;
        float chartHeight = height - paddingTop - paddingBottom;

        int maxCount = 1;
        for (BarItem item : items) {
            if (item.count > maxCount) maxCount = item.count;
        }

        int count = items.size();
        float totalSpacing = chartWidth * 0.25f;
        float barSpacing = totalSpacing / Math.max(1, count - 1);
        float barWidth = (chartWidth - (barSpacing * (count - 1))) / count;
        float cornerRadius = Math.min(barWidth / 2f, dpToPx(6));

        // Draw horizontal baseline
        float baselineY = height - paddingBottom;
        canvas.drawLine(paddingLeft, baselineY, width - paddingRight, baselineY, gridPaint);

        for (int i = 0; i < count; i++) {
            BarItem item = items.get(i);
            float x = paddingLeft + (i * (barWidth + barSpacing));

            float barHeightRatio = (float) item.count / maxCount;
            float targetBarHeight = Math.max(dpToPx(4), chartHeight * barHeightRatio);
            float currentBarHeight = targetBarHeight * animationProgress;
            float y = baselineY - currentBarHeight;

            RectF rect = new RectF(x, y, x + barWidth, baselineY);

            Paint paint;
            if (i == selectedIndex) {
                paint = selectedPaint;
            } else if (item.isPeak && item.count > 0) {
                paint = peakPaint;
            } else if (item.count > 0) {
                paint = barPaint;
            } else {
                paint = emptyBarPaint;
            }

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);

            // Draw label every step (e.g. every 6th bar for 24 hours, or every day for 7 days)
            boolean shouldDrawLabel = (count <= 7) || (i % 6 == 0) || (i == count - 1);
            if (shouldDrawLabel && item.label != null) {
                canvas.drawText(item.label, x + (barWidth / 2f), height - dpToPx(6), textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.isEmpty()) return super.onTouchEvent(event);

        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            float touchX = event.getX();
            int width = getWidth();
            float paddingLeft = dpToPx(8);
            float paddingRight = dpToPx(8);
            float chartWidth = width - paddingLeft - paddingRight;

            int count = items.size();
            float totalSpacing = chartWidth * 0.25f;
            float barSpacing = totalSpacing / Math.max(1, count - 1);
            float barWidth = (chartWidth - (barSpacing * (count - 1))) / count;

            int index = (int) ((touchX - paddingLeft) / (barWidth + barSpacing));
            if (index >= 0 && index < count && index != selectedIndex) {
                selectedIndex = index;
                invalidate();
                if (listener != null) {
                    listener.onBarSelected(index, items.get(index));
                }
                return true;
            }
        }
        return true;
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
