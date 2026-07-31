package com.marathonrecomp.launcher.input;

import android.hardware.input.InputManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical controller support.
 *
 * <p>Android already normalises most pads to the standard KEYCODE_BUTTON_* / AXIS_*
 * scheme, and those map one-to-one onto the Xbox 360 layout the game expects, so events
 * are translated straight into the same {@link PadState} the touch overlay writes to.</p>
 *
 * <p>The handler also watches for devices coming and going: as soon as a real pad is
 * present the touch overlay is told to disappear, and it comes back when the last pad is
 * unplugged.</p>
 */
public final class GamepadHandler implements InputManager.InputDeviceListener {

    private static final String TAG = "MarathonDroid/Gamepad";

    /** Sticks below this are treated as centred; most pads rest around 0.05. */
    private static final float DEAD_ZONE = 0.15f;

    public interface Listener {
        /** Called on the UI thread whenever the "a real pad is attached" state flips. */
        void onGamepadConnectionChanged(boolean connected);
    }

    private final PadState pad;
    private final InputManager inputManager;
    private final Listener listener;

    private final List<Integer> connected = new ArrayList<>();
    private boolean lastReportedState;

    public GamepadHandler(InputManager inputManager, PadState pad, Listener listener) {
        this.inputManager = inputManager;
        this.pad = pad;
        this.listener = listener;
    }

    // -------------------------------------------------------------- lifecycle ----

    public void start() {
        connected.clear();

        for (int id : InputDevice.getDeviceIds()) {
            if (isGamepad(InputDevice.getDevice(id))) {
                connected.add(id);
            }
        }

        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }

        notifyState();
    }

    public void stop() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }

        connected.clear();
    }

    public boolean isConnected() {
        return !connected.isEmpty();
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }

        int sources = device.getSources();
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;

        return gamepad || joystick;
    }

    private void notifyState() {
        boolean state = isConnected();

        if (state == lastReportedState) {
            return;
        }

        lastReportedState = state;

        if (state) {
            // Wipe whatever the touch overlay left behind before the pad takes over.
            pad.reset();
            pad.setSource(PadState.SOURCE_GAMEPAD);
        } else {
            pad.reset();
            pad.setSource(PadState.SOURCE_TOUCH);
        }

        pad.push(true);

        Log.i(TAG, state ? "Physical gamepad connected - hiding touch controls"
                : "No physical gamepad - showing touch controls");

        if (listener != null) {
            listener.onGamepadConnectionChanged(state);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        if (isGamepad(InputDevice.getDevice(deviceId)) && !connected.contains(deviceId)) {
            connected.add(deviceId);
            notifyState();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (connected.remove(Integer.valueOf(deviceId))) {
            notifyState();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        boolean nowGamepad = isGamepad(InputDevice.getDevice(deviceId));
        boolean known = connected.contains(deviceId);

        if (nowGamepad && !known) {
            connected.add(deviceId);
            notifyState();
        } else if (!nowGamepad && known) {
            connected.remove(Integer.valueOf(deviceId));
            notifyState();
        }
    }

    // ------------------------------------------------------------------ input ----

    /** @return true when the key belonged to a controller and was consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (!isFromGamepad(event.getSource())) {
            return false;
        }

        int mask = keyToMask(event.getKeyCode());

        if (mask == 0) {
            // Triggers are digital on some pads.
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) {
                pad.setTrigger(true, event.getAction() == KeyEvent.ACTION_DOWN ? 255 : 0);
                pushGamepad();
                return true;
            }

            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
                pad.setTrigger(false, event.getAction() == KeyEvent.ACTION_DOWN ? 255 : 0);
                pushGamepad();
                return true;
            }

            return false;
        }

        if (event.getRepeatCount() > 0 && event.getAction() == KeyEvent.ACTION_DOWN) {
            return true;
        }

        pad.setButton(mask, event.getAction() == KeyEvent.ACTION_DOWN);
        pushGamepad();
        return true;
    }

    /** @return true when the motion event came from a stick / trigger and was consumed. */
    public boolean onMotionEvent(MotionEvent event) {
        if (!isFromGamepad(event.getSource()) || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }

        pad.setStickNormalised(true,
                applyDeadZone(axis(event, MotionEvent.AXIS_X)),
                -applyDeadZone(axis(event, MotionEvent.AXIS_Y)));

        // Right stick sits on Z/RZ on most pads, but some report RX/RY instead.
        float rx = axis(event, MotionEvent.AXIS_Z);
        float ry = axis(event, MotionEvent.AXIS_RZ);

        if (rx == 0f && ry == 0f) {
            rx = axis(event, MotionEvent.AXIS_RX);
            ry = axis(event, MotionEvent.AXIS_RY);
        }

        pad.setStickNormalised(false, applyDeadZone(rx), -applyDeadZone(ry));

        // Analog triggers: LTRIGGER/RTRIGGER on Xbox pads, BRAKE/GAS on some others.
        float lt = Math.max(axis(event, MotionEvent.AXIS_LTRIGGER), axis(event, MotionEvent.AXIS_BRAKE));
        float rt = Math.max(axis(event, MotionEvent.AXIS_RTRIGGER), axis(event, MotionEvent.AXIS_GAS));

        pad.setTrigger(true, Math.round(clamp01(lt) * 255f));
        pad.setTrigger(false, Math.round(clamp01(rt) * 255f));

        // Hat switch: many pads report the d-pad as an axis instead of key events.
        float hatX = axis(event, MotionEvent.AXIS_HAT_X);
        float hatY = axis(event, MotionEvent.AXIS_HAT_Y);

        pad.setButton(Binding.DPAD_LEFT.mask(), hatX < -0.5f);
        pad.setButton(Binding.DPAD_RIGHT.mask(), hatX > 0.5f);
        pad.setButton(Binding.DPAD_UP.mask(), hatY < -0.5f);
        pad.setButton(Binding.DPAD_DOWN.mask(), hatY > 0.5f);

        pushGamepad();
        return true;
    }

    private void pushGamepad() {
        pad.setSource(PadState.SOURCE_GAMEPAD);
        pad.push(true);
    }

    private static float axis(MotionEvent event, int axis) {
        return event.getAxisValue(axis);
    }

    private static float applyDeadZone(float value) {
        if (Math.abs(value) < DEAD_ZONE) {
            return 0f;
        }

        // Rescale so the first movement past the dead zone isn't a jump.
        float sign = Math.signum(value);
        return sign * ((Math.abs(value) - DEAD_ZONE) / (1f - DEAD_ZONE));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static int keyToMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return Binding.A.mask();
            case KeyEvent.KEYCODE_BUTTON_B:
                return Binding.B.mask();
            case KeyEvent.KEYCODE_BUTTON_X:
                return Binding.X.mask();
            case KeyEvent.KEYCODE_BUTTON_Y:
                return Binding.Y.mask();
            case KeyEvent.KEYCODE_BUTTON_L1:
                return Binding.LB.mask();
            case KeyEvent.KEYCODE_BUTTON_R1:
                return Binding.RB.mask();
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return Binding.L3.mask();
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return Binding.R3.mask();
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return Binding.START.mask();
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return Binding.BACK.mask();
            case KeyEvent.KEYCODE_DPAD_UP:
                return Binding.DPAD_UP.mask();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return Binding.DPAD_DOWN.mask();
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return Binding.DPAD_LEFT.mask();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return Binding.DPAD_RIGHT.mask();
            default:
                return 0;
        }
    }
}
