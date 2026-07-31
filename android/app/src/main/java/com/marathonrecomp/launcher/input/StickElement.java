package com.marathonrecomp.launcher.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Analog thumb stick. The knob follows the finger inside the ring and reports a
 * normalised vector; releasing snaps it back to centre and zeroes the axis.
 */
public class StickElement extends ControlElement {

    private static final float RING_DP = 132f;
    private static final float KNOB_RATIO = 0.42f;
    /** Ignore the very centre so a resting thumb doesn't creep the camera. */
    private static final float DEAD_ZONE = 0.12f;

    private int pointerId = -1;
    private float knobX;
    private float knobY;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StickElement(TouchOverlayView view, ControlDescription desc) {
        super(view, desc);
        ring.setStyle(Paint.Style.STROKE);
        knob.setStyle(Paint.Style.FILL);
    }

    private float radius() {
        return dp(RING_DP / 2f) * desc.scale;
    }

    @Override
    public float halfWidth() {
        return radius();
    }

    @Override
    public float halfHeight() {
        return radius();
    }

    @Override
    public boolean isPointOver(float x, float y) {
        float dx = x - centerX();
        float dy = y - centerY();
        float r = radius() * 1.1f;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    public boolean handleTouch(MotionEvent event, int pointerIndex, int pointerId) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (this.pointerId < 0 && isPointOver(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    this.pointerId = pointerId;
                    track(event.getX(pointerIndex), event.getY(pointerIndex));
                    return true;
                }

                return false;

            case MotionEvent.ACTION_MOVE:
                if (this.pointerId >= 0) {
                    int index = event.findPointerIndex(this.pointerId);

                    if (index >= 0) {
                        track(event.getX(index), event.getY(index));
                        return true;
                    }
                }

                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || pointerId == this.pointerId) {
                    release();
                    view.invalidate();
                    return true;
                }

                return false;

            default:
                return false;
        }
    }

    private void track(float x, float y) {
        float r = radius();
        float dx = x - centerX();
        float dy = y - centerY();
        float len = (float) Math.hypot(dx, dy);

        if (len > r) {
            dx = dx / len * r;
            dy = dy / len * r;
            len = r;
        }

        knobX = dx;
        knobY = dy;

        float nx = dx / r;
        float ny = dy / r;
        float magnitude = (float) Math.hypot(nx, ny);

        if (magnitude < DEAD_ZONE) {
            nx = 0f;
            ny = 0f;
        } else {
            // Rescale past the dead zone so the usable range still reaches full deflection.
            float scaled = (magnitude - DEAD_ZONE) / (1f - DEAD_ZONE) / magnitude;
            nx *= scaled;
            ny *= scaled;
        }

        // Screen Y grows downwards, XInput's Y grows upwards.
        view.pad().setStickNormalised(desc.binding != Binding.RIGHT_STICK, nx, -ny);
        view.pushPad();
        view.invalidate();
    }

    @Override
    public void release() {
        if (pointerId >= 0) {
            view.pad().setStickNormalised(desc.binding != Binding.RIGHT_STICK, 0f, 0f);
            view.pushPad();
        }

        pointerId = -1;
        knobX = 0f;
        knobY = 0f;
    }

    @Override
    public boolean isActive() {
        return pointerId >= 0;
    }

    @Override
    public void draw(Canvas canvas) {
        float cx = centerX();
        float cy = centerY();
        float r = radius();
        int base = desc.alpha;

        ring.setColor(Color.argb(Math.min(255, base + 30), 255, 255, 255));
        ring.setStrokeWidth(dp(2.5f));

        if (selected) {
            ring.setColor(0xFF3DDC84);
            ring.setStrokeWidth(dp(3.5f));
        }

        canvas.drawCircle(cx, cy, r, ring);

        knob.setColor(Color.argb(isActive() ? Math.min(255, base + 60) : (int) (base * 0.5f), 255, 255, 255));
        canvas.drawCircle(cx + knobX, cy + knobY, r * KNOB_RATIO, knob);
    }
}
