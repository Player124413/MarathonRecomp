package com.marathonrecomp.launcher.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * Four-way d-pad drawn as a cross. Diagonals are supported: the touch point is compared
 * against the centre and any axis past a small threshold is pressed, so up+right works
 * the same way it does on a real pad.
 */
public class DpadElement extends ControlElement {

    private static final float SIZE_DP = 132f;
    /** Fraction of the half-size a finger must pass before an axis counts as pressed. */
    private static final float THRESHOLD = 0.22f;

    private int pointerId = -1;
    private int pressedMask;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public DpadElement(TouchOverlayView view, ControlDescription desc) {
        super(view, desc);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private float half() {
        return dp(SIZE_DP / 2f) * desc.scale;
    }

    @Override
    public float halfWidth() {
        return half();
    }

    @Override
    public float halfHeight() {
        return half();
    }

    @Override
    public boolean handleTouch(MotionEvent event, int pointerIndex, int pointerId) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (this.pointerId < 0 && isPointOver(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    this.pointerId = pointerId;
                    view.haptic();
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
        float h = half();
        float dx = (x - centerX()) / h;
        float dy = (y - centerY()) / h;

        int mask = 0;

        if (dy < -THRESHOLD) {
            mask |= Binding.DPAD_UP.mask();
        } else if (dy > THRESHOLD) {
            mask |= Binding.DPAD_DOWN.mask();
        }

        if (dx < -THRESHOLD) {
            mask |= Binding.DPAD_LEFT.mask();
        } else if (dx > THRESHOLD) {
            mask |= Binding.DPAD_RIGHT.mask();
        }

        if (mask != pressedMask) {
            applyMask(mask);
            view.invalidate();
        }
    }

    private void applyMask(int mask) {
        int all = Binding.DPAD_UP.mask() | Binding.DPAD_DOWN.mask()
                | Binding.DPAD_LEFT.mask() | Binding.DPAD_RIGHT.mask();

        for (int bit = 1; bit <= all; bit <<= 1) {
            if ((all & bit) != 0) {
                view.pad().setButton(bit, (mask & bit) != 0);
            }
        }

        pressedMask = mask;
        view.pushPad();
    }

    @Override
    public void release() {
        if (pressedMask != 0) {
            applyMask(0);
        }

        pointerId = -1;
    }

    @Override
    public boolean isActive() {
        return pointerId >= 0;
    }

    @Override
    public void draw(Canvas canvas) {
        float cx = centerX();
        float cy = centerY();
        float h = half();
        float arm = h * 0.36f;
        int base = desc.alpha;

        fill.setColor(Color.argb((int) (base * 0.38f), 255, 255, 255));
        stroke.setColor(Color.argb(Math.min(255, base + 50), 255, 255, 255));
        stroke.setStrokeWidth(dp(2f));

        if (selected) {
            stroke.setColor(0xFF3DDC84);
            stroke.setStrokeWidth(dp(3f));
        }

        path.reset();
        path.moveTo(cx - arm, cy - h);
        path.lineTo(cx + arm, cy - h);
        path.lineTo(cx + arm, cy - arm);
        path.lineTo(cx + h, cy - arm);
        path.lineTo(cx + h, cy + arm);
        path.lineTo(cx + arm, cy + arm);
        path.lineTo(cx + arm, cy + h);
        path.lineTo(cx - arm, cy + h);
        path.lineTo(cx - arm, cy + arm);
        path.lineTo(cx - h, cy + arm);
        path.lineTo(cx - h, cy - arm);
        path.lineTo(cx - arm, cy - arm);
        path.close();

        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);

        // Light up whichever direction is currently held.
        drawActiveArm(canvas, Binding.DPAD_UP.mask(), cx, cy - h * 0.66f, arm * 0.55f, base);
        drawActiveArm(canvas, Binding.DPAD_DOWN.mask(), cx, cy + h * 0.66f, arm * 0.55f, base);
        drawActiveArm(canvas, Binding.DPAD_LEFT.mask(), cx - h * 0.66f, cy, arm * 0.55f, base);
        drawActiveArm(canvas, Binding.DPAD_RIGHT.mask(), cx + h * 0.66f, cy, arm * 0.55f, base);
    }

    private void drawActiveArm(Canvas canvas, int mask, float x, float y, float r, int base) {
        if ((pressedMask & mask) == 0) {
            return;
        }

        fill.setColor(Color.argb(Math.min(255, base + 90), 255, 255, 255));
        canvas.drawCircle(x, y, r, fill);
        fill.setColor(Color.argb((int) (base * 0.38f), 255, 255, 255));
    }
}
