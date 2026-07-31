package com.marathonrecomp.launcher.input;

import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * Base class for anything drawn on the touch overlay.
 *
 * <p>Elements own their {@link ControlDescription} (so the editor can mutate position,
 * scale, opacity and binding live) and translate finger movement into
 * {@link PadState} changes.</p>
 */
public abstract class ControlElement {

    protected final TouchOverlayView view;
    public final ControlDescription desc;

    /** Drawn with a highlight ring while selected in the editor. */
    public boolean selected;

    protected ControlElement(TouchOverlayView view, ControlDescription desc) {
        this.view = view;
        this.desc = desc;
    }

    // ------------------------------------------------------------- geometry ----

    protected float dp(float value) {
        return value * view.density();
    }

    public float centerX() {
        return desc.x * view.getWidth();
    }

    public float centerY() {
        return desc.y * view.getHeight();
    }

    /** Half width in pixels, including the size multiplier. */
    public abstract float halfWidth();

    /** Half height in pixels, including the size multiplier. */
    public abstract float halfHeight();

    public boolean isPointOver(float x, float y) {
        return Math.abs(x - centerX()) <= halfWidth() && Math.abs(y - centerY()) <= halfHeight();
    }

    // ---------------------------------------------------------------- input ----

    /**
     * Handles one touch event. Returns true when this element consumed it, in which case
     * the overlay stops looking for other candidates for that pointer.
     */
    public abstract boolean handleTouch(MotionEvent event, int pointerIndex, int pointerId);

    /** Releases whatever this element is holding down (overlay hidden, pad connected, ...). */
    public abstract void release();

    /** True while a finger is on this element. */
    public abstract boolean isActive();

    // ----------------------------------------------------------------- draw ----

    public abstract void draw(Canvas canvas);

    // --------------------------------------------------------------- factory ----

    public static ControlElement create(TouchOverlayView view, ControlDescription desc) {
        switch (desc.type) {
            case STICK:
                return new StickElement(view, desc);
            case DPAD:
                return new DpadElement(view, desc);
            case BUTTON:
            default:
                return new ButtonElement(view, desc);
        }
    }
}
