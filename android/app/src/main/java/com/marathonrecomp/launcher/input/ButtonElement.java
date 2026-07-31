package com.marathonrecomp.launcher.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * A single tappable button bound to one {@link Binding}: a face button, shoulder, trigger,
 * menu button, or one of the launcher's own actions.
 */
public class ButtonElement extends ControlElement {

    private static final float CIRCLE_DP = 78f;
    private static final float RECT_W_DP = 104f;
    private static final float RECT_H_DP = 58f;
    /** Extra forgiveness around the visible edge so buttons feel reachable in a hurry. */
    private static final float SLOP_DP = 10f;

    private int pointerId = -1;
    private boolean latched;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ButtonElement(TouchOverlayView view, ControlDescription desc) {
        super(view, desc);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    @Override
    public float halfWidth() {
        return dp((desc.circle ? CIRCLE_DP : RECT_W_DP) / 2f) * desc.scale;
    }

    @Override
    public float halfHeight() {
        return dp((desc.circle ? CIRCLE_DP : RECT_H_DP) / 2f) * desc.scale;
    }

    @Override
    public boolean isPointOver(float x, float y) {
        float slop = dp(SLOP_DP);
        float cx = centerX();
        float cy = centerY();

        if (desc.circle) {
            float r = halfWidth() + slop;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy <= r * r;
        }

        return Math.abs(x - cx) <= halfWidth() + slop && Math.abs(y - cy) <= halfHeight() + slop;
    }

    @Override
    public boolean handleTouch(MotionEvent event, int pointerIndex, int pointerId) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (this.pointerId < 0 && isPointOver(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    this.pointerId = pointerId;
                    view.haptic();

                    if (desc.toggle) {
                        latched = !latched;
                        apply(latched);
                    } else {
                        apply(true);
                    }

                    view.invalidate();
                    return true;
                }

                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (action == MotionEvent.ACTION_CANCEL || pointerId == this.pointerId) {
                    if (!desc.toggle) {
                        apply(false);
                    }

                    this.pointerId = -1;
                    view.invalidate();
                    return action != MotionEvent.ACTION_CANCEL;
                }

                return false;

            default:
                return false;
        }
    }

    private void apply(boolean down) {
        if (desc.binding.isLauncherAction()) {
            if (down) {
                view.onLauncherAction(desc.binding);
            }

            return;
        }

        if (desc.binding.isTrigger()) {
            view.pad().setTrigger(desc.binding == Binding.LT, down ? 255 : 0);
        } else {
            view.pad().setButton(desc.binding.mask(), down);
        }

        view.pushPad();
    }

    @Override
    public void release() {
        if (pointerId >= 0 && !desc.toggle) {
            apply(false);
        }

        if (latched) {
            latched = false;
            apply(false);
        }

        pointerId = -1;
    }

    @Override
    public boolean isActive() {
        return pointerId >= 0 || latched;
    }

    @Override
    public void draw(Canvas canvas) {
        float cx = centerX();
        float cy = centerY();
        float hw = halfWidth();
        float hh = halfHeight();
        boolean active = isActive();

        int base = desc.alpha;
        int fillAlpha = active ? Math.min(255, base + 70) : (int) (base * 0.38f);

        fill.setColor(Color.argb(fillAlpha, 255, 255, 255));
        stroke.setColor(Color.argb(Math.min(255, base + 50), 255, 255, 255));
        stroke.setStrokeWidth(dp(2f));

        if (selected) {
            stroke.setColor(0xFF3DDC84);
            stroke.setStrokeWidth(dp(3f));
        }

        if (desc.circle) {
            canvas.drawCircle(cx, cy, hw, fill);
            canvas.drawCircle(cx, cy, hw, stroke);
        } else {
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float r = dp(10f);
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.drawRoundRect(rect, r, r, stroke);
        }

        text.setColor(Color.argb(Math.min(255, base + 90), 255, 255, 255));
        text.setTextSize(Math.min(hw, hh) * 0.72f);

        String label = desc.label();
        canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2f, text);
    }
}
