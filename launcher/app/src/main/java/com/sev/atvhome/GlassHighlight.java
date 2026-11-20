package com.sev.atvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The single translucent "glass" focus highlight. It springs from target to target
 * (overshoot interpolation of position, size and corner radius) instead of every tile
 * drawing its own border. Shaders are built once for a unit height and stretched with a
 * local matrix, so animating allocates nothing per frame.
 */
public class GlassHighlight extends View {

    private final RectF shown = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private float shownRadius, fromRadius, toRadius;
    private boolean hasTarget;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient fillShader, strokeShader;
    private final Matrix matrix = new Matrix();
    private final ValueAnimator animator;
    private final float density;

    public GlassHighlight(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);

        fillShader = new LinearGradient(0, 0, 0, 1,
                new int[]{0x66FFFFFF, 0x38FFFFFF, 0x24FFFFFF}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        fill.setShader(fillShader);

        strokeShader = new LinearGradient(0, 0, 0, 1,
                new int[]{0xCCFFFFFF, 0x59FFFFFF, 0x33FFFFFF}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        stroke.setShader(strokeShader);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * density);

        sheen.setColor(0x1FFFFFFF);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(340);
        animator.setInterpolator(new OvershootInterpolator(1.3f));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            shown.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            shownRadius = Math.max(0f, lerp(fromRadius, toRadius, t));
            invalidate();
        });
    }

    /** Move to {@code target} (in this view's coordinates). */
    public void moveTo(RectF target, float radius, boolean animate) {
        animator.cancel();
        if (!hasTarget || !animate) {
            shown.set(target);
            shownRadius = radius;
            to.set(target);
            toRadius = radius;
            hasTarget = true;
            invalidate();
            return;
        }
        from.set(shown);
        fromRadius = shownRadius;
        to.set(target);
        toRadius = radius;
        animator.start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasTarget || shown.width() <= 0 || shown.height() <= 0) return;
        matrix.setScale(1f, shown.height());
        matrix.postTranslate(0, shown.top);
        fillShader.setLocalMatrix(matrix);
        strokeShader.setLocalMatrix(matrix);

        canvas.drawRoundRect(shown, shownRadius, shownRadius, fill);
        // Thin specular line along the top edge sells the glass without any blur
        float inset = Math.max(shownRadius, 6 * density);
        canvas.drawRect(shown.left + inset, shown.top + density,
                shown.right - inset, shown.top + 2.2f * density, sheen);
        float half = stroke.getStrokeWidth() / 2f;
        canvas.drawRoundRect(shown.left + half, shown.top + half, shown.right - half,
                shown.bottom - half, shownRadius, shownRadius, stroke);
    }
}
