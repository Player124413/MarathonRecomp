package com.marathonrecomp.launcher.input;

/**
 * Mutable Xbox 360 pad state, shared by the touch overlay and the physical gamepad handler.
 *
 * <p>Both input sources write into the same instance; whoever owns the frame calls
 * {@link #push} which forwards it to the native shared-memory writer. Values match XInput
 * exactly: buttons are a 16-bit mask, triggers 0..255, sticks -32768..32767.</p>
 */
public final class PadState {

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_TOUCH = 1;
    public static final int SOURCE_GAMEPAD = 2;

    private int buttons;
    private int leftTrigger;
    private int rightTrigger;
    private int thumbLX;
    private int thumbLY;
    private int thumbRX;
    private int thumbRY;

    private int source = SOURCE_TOUCH;
    private boolean dirty = true;

    public synchronized void setButton(int mask, boolean down) {
        if (mask == 0) {
            return;
        }

        int updated = down ? (buttons | mask) : (buttons & ~mask);

        if (updated != buttons) {
            buttons = updated;
            dirty = true;
        }
    }

    public synchronized boolean isButtonDown(int mask) {
        return mask != 0 && (buttons & mask) != 0;
    }

    public synchronized void setTrigger(boolean left, int value) {
        int v = clamp(value, 0, 255);

        if (left) {
            if (leftTrigger != v) {
                leftTrigger = v;
                dirty = true;
            }
        } else if (rightTrigger != v) {
            rightTrigger = v;
            dirty = true;
        }
    }

    public synchronized void setStick(boolean left, int x, int y) {
        int cx = clamp(x, -32768, 32767);
        int cy = clamp(y, -32768, 32767);

        if (left) {
            if (thumbLX != cx || thumbLY != cy) {
                thumbLX = cx;
                thumbLY = cy;
                dirty = true;
            }
        } else if (thumbRX != cx || thumbRY != cy) {
            thumbRX = cx;
            thumbRY = cy;
            dirty = true;
        }
    }

    /** Normalised stick setter, -1..1 with y already flipped to XInput's "up is positive". */
    public void setStickNormalised(boolean left, float x, float y) {
        setStick(left, Math.round(x * 32767f), Math.round(y * 32767f));
    }

    public synchronized void setSource(int source) {
        if (this.source != source) {
            this.source = source;
            dirty = true;
        }
    }

    /** Zeroes everything (used when the overlay hides, or a pad disconnects). */
    public synchronized void reset() {
        buttons = 0;
        leftTrigger = 0;
        rightTrigger = 0;
        thumbLX = thumbLY = thumbRX = thumbRY = 0;
        dirty = true;
    }

    /**
     * Sends the state to the game if anything changed. Cheap enough to call on every
     * touch event and every gamepad frame.
     *
     * @param connected false makes the game fall back to "no controller connected"
     */
    public void push(boolean connected) {
        int b, lt, rt, lx, ly, rx, ry, src;

        synchronized (this) {
            if (!dirty) {
                return;
            }

            dirty = false;
            b = buttons;
            lt = leftTrigger;
            rt = rightTrigger;
            lx = thumbLX;
            ly = thumbLY;
            rx = thumbRX;
            ry = thumbRY;
            src = source;
        }

        com.marathonrecomp.launcher.NativeBridge.vpadPush(0, connected, src, b, lt, rt, lx, ly, rx, ry);
    }

    /** Forces the next {@link #push} to write even if nothing changed. */
    public synchronized void invalidate() {
        dirty = true;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
