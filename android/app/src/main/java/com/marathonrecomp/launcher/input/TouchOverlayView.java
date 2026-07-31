package com.marathonrecomp.launcher.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen Xbox 360 pad.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>Play</b> - multi-touch is routed to the elements, which drive a {@link PadState}
 *       that is pushed to the game through shared memory.</li>
 *   <li><b>Edit</b> - tap to select, drag to move, and the hosting activity shows sliders
 *       for size and opacity. Nothing is sent to the game.</li>
 * </ul>
 *
 * <p>When a physical gamepad is connected the whole overlay hides itself
 * ({@link #setGamepadConnected}), because a real pad makes the touch controls redundant
 * and they would otherwise sit on top of the picture.</p>
 */
public class TouchOverlayView extends View {

    /** Callbacks for the launcher-only buttons (Hide / Edit / Menu). */
    public interface ActionListener {
        void onLauncherAction(Binding binding);
    }

    /** Editor callbacks; only used by the layout editor activity. */
    public interface EditorListener {
        /** null when the selection was cleared. */
        void onSelectionChanged(ControlElement element);
    }

    private final List<ControlElement> elements = new ArrayList<>();
    private final PadState pad = new PadState();
    private final float density;

    private boolean editMode;
    private boolean controlsHidden;
    private boolean gamepadConnected;
    private boolean hapticEnabled = true;

    private ControlElement selected;
    private ControlElement dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    private ActionListener actionListener;
    private EditorListener editorListener;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TouchOverlayView(Context context) {
        this(context, null);
    }

    public TouchOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(density);
        gridPaint.setColor(0x22FFFFFF);

        hintPaint.setColor(0x99FFFFFF);
        hintPaint.setTextSize(14f * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        setFocusable(false);
        load();
    }

    // ------------------------------------------------------------- lifecycle ----

    /** Rebuilds the elements from the saved layout (or the stock one). */
    public void load() {
        setLayout(ControlLayout.load(getContext()));
    }

    public void setLayout(List<ControlDescription> descriptions) {
        releaseAll();
        elements.clear();

        for (ControlDescription d : descriptions) {
            elements.add(ControlElement.create(this, d));
        }

        selected = null;
        invalidate();
    }

    public void save() {
        List<ControlDescription> list = new ArrayList<>(elements.size());

        for (ControlElement e : elements) {
            list.add(e.desc);
        }

        ControlLayout.save(getContext(), list);
    }

    public List<ControlElement> elements() {
        return elements;
    }

    public PadState pad() {
        return pad;
    }

    public float density() {
        return density;
    }

    public void setActionListener(ActionListener listener) {
        actionListener = listener;
    }

    public void setEditorListener(EditorListener listener) {
        editorListener = listener;
    }

    public void setHapticEnabled(boolean enabled) {
        hapticEnabled = enabled;
    }

    // ------------------------------------------------------------------ modes ----

    public void setEditMode(boolean edit) {
        if (editMode == edit) {
            return;
        }

        editMode = edit;
        releaseAll();
        selected = null;
        invalidate();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Hide / show every control (bound to the overlay's own "Hide" button). */
    public void toggleHidden() {
        controlsHidden = !controlsHidden;
        releaseAll();
        invalidate();
    }

    /**
     * A physical gamepad appeared or went away. While one is connected the entire overlay
     * disappears and stops eating touches, and the touch pad state is zeroed so nothing
     * stays stuck down.
     */
    public void setGamepadConnected(boolean connected) {
        if (gamepadConnected == connected) {
            return;
        }

        gamepadConnected = connected;

        if (connected) {
            releaseAll();
        }

        setVisibility(connected ? GONE : VISIBLE);
        invalidate();
    }

    public boolean isGamepadConnected() {
        return gamepadConnected;
    }

    private boolean isVisibleElement(ControlElement e) {
        if (!controlsHidden) {
            return true;
        }

        // The Hide button has to stay reachable, otherwise there's no way back.
        return e.desc.binding == Binding.TOGGLE_CONTROLS;
    }

    // ------------------------------------------------------------------ input ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gamepadConnected) {
            return false;
        }

        return editMode ? handleEditTouch(event) : handlePlayTouch(event);
    }

    private boolean handlePlayTouch(MotionEvent event) {
        int action = event.getActionMasked();
        boolean consumed = false;

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            // Move/cancel carries every pointer at once, so offer it to all elements.
            for (ControlElement e : elements) {
                if (isVisibleElement(e) && e.handleTouch(event, 0, -1)) {
                    consumed = true;
                }
            }

            return consumed || action == MotionEvent.ACTION_CANCEL;
        }

        int index = event.getActionIndex();
        int id = event.getPointerId(index);

        // Walk backwards so the element drawn on top is also the one that gets the touch,
        // which is the same rule the editor uses for selection.
        for (int i = elements.size() - 1; i >= 0; i--) {
            ControlElement e = elements.get(i);

            if (isVisibleElement(e) && e.handleTouch(event, index, id)) {
                return true;
            }
        }

        return false;
    }

    private boolean handleEditTouch(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                ControlElement hit = findAt(x, y);
                select(hit);

                if (hit != null) {
                    dragging = hit;
                    dragOffsetX = x - hit.centerX();
                    dragOffsetY = y - hit.centerY();
                }

                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE:
                if (dragging != null && getWidth() > 0 && getHeight() > 0) {
                    // Keep the element fully on screen while dragging.
                    float hw = dragging.halfWidth();
                    float hh = dragging.halfHeight();
                    float cx = clamp(x - dragOffsetX, hw, getWidth() - hw);
                    float cy = clamp(y - dragOffsetY, hh, getHeight() - hh);

                    dragging.desc.x = cx / getWidth();
                    dragging.desc.y = cy / getHeight();
                    invalidate();
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = null;
                return true;

            default:
                return true;
        }
    }

    private ControlElement findAt(float x, float y) {
        // Reverse order: the element drawn last (on top) wins the tap.
        for (int i = elements.size() - 1; i >= 0; i--) {
            ControlElement e = elements.get(i);

            if (e.isPointOver(x, y)) {
                return e;
            }
        }

        return null;
    }

    private void select(ControlElement element) {
        if (selected != null) {
            selected.selected = false;
        }

        selected = element;

        if (selected != null) {
            selected.selected = true;
        }

        if (editorListener != null) {
            editorListener.onSelectionChanged(selected);
        }
    }

    public ControlElement selected() {
        return selected;
    }

    /** Adds a control at the centre of the screen and selects it (editor "add" button). */
    public ControlElement addElement(ControlDescription desc) {
        ControlElement e = ControlElement.create(this, desc);
        elements.add(e);
        select(e);
        invalidate();
        return e;
    }

    /** Removes the selected control (editor "delete" button). */
    public void removeSelected() {
        if (selected == null) {
            return;
        }

        selected.release();
        elements.remove(selected);
        select(null);
        invalidate();
    }

    // ------------------------------------------------------------------- pad ----

    /** Pushes the merged pad state to the game; ignored while editing. */
    public void pushPad() {
        if (editMode) {
            return;
        }

        pad.setSource(PadState.SOURCE_TOUCH);
        pad.push(true);
    }

    private void releaseAll() {
        for (ControlElement e : elements) {
            e.release();
        }

        pad.reset();

        if (!editMode) {
            // Still "connected": the launcher always presents exactly one pad to the game,
            // whether a finger or a real controller is driving it. Reporting a disconnect
            // here would make the game fall back to keyboard prompts the moment a physical
            // pad took over from the touch overlay.
            pad.push(true);
        }
    }

    public void onLauncherAction(Binding binding) {
        if (binding == Binding.TOGGLE_CONTROLS) {
            toggleHidden();
        }

        if (actionListener != null) {
            actionListener.onLauncherAction(binding);
        }
    }

    public void haptic() {
        if (!hapticEnabled) {
            return;
        }

        try {
            Vibrator vibrator;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The subtlest predefined effect; exactly what a button press should feel like.
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
            // A missing motor must never break input handling.
        }
    }

    // ------------------------------------------------------------------ draw ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (gamepadConnected) {
            return;
        }

        if (editMode) {
            drawEditorBackdrop(canvas);
        }

        for (ControlElement e : elements) {
            if (isVisibleElement(e)) {
                e.draw(canvas);
            }
        }
    }

    private void drawEditorBackdrop(Canvas canvas) {
        canvas.drawColor(Color.argb(120, 0, 0, 0));

        int step = (int) (48 * density);

        for (int x = step; x < getWidth(); x += step) {
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }

        for (int y = step; y < getHeight(); y += step) {
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }

        if (selected == null) {
            canvas.drawText(getContext().getString(
                            com.marathonrecomp.launcher.R.string.editor_hint_select),
                    getWidth() / 2f, 28 * density, hintPaint);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) {
            return lo;
        }

        return v < lo ? lo : (v > hi ? hi : v);
    }
}
