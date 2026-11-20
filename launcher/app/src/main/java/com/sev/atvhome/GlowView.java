package com.sev.atvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Soft elliptical ambient glow behind the dock title, tinted with the focused app's colour. */
public class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private ValueAnimator animator;
    private int color = 0xFF3A3F66;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public void setGlowColor(int target, boolean animate) {
        if (animator != null) animator.cancel();
        if (!animate) {
            color = target;
            rebuild();
            return;
        }
        final int start = color;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(420);
        animator.addUpdateListener(a -> {
            color = (int) argb.evaluate((float) a.getAnimatedValue(), start, target);
            rebuild();
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuild();
    }

    private void rebuild() {
        int w = getWidth();
        if (w == 0) return;
        float r = w / 2f;
        int rgb = color & 0x00FFFFFF;
        paint.setShader(new RadialGradient(r, r, r,
                new int[]{0x73000000 | rgb, 0x2E000000 | rgb, rgb},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Draw a circle of diameter w, squashed vertically into an ellipse of height h
        canvas.save();
        canvas.scale(1f, h / (float) w);
        canvas.drawCircle(w / 2f, w / 2f, w / 2f, paint);
        canvas.restore();
    }
}
