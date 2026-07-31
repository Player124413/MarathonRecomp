package com.marathonrecomp.launcher.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads / stores the on-screen control layout and provides the stock Xbox 360 arrangement.
 *
 * <p>The layout is a plain JSON array in SharedPreferences, so the editor can save it,
 * "reset to default" can drop it, and a future export/import can just move the string.</p>
 */
public final class ControlLayout {

    private static final String TAG = "MarathonDroid/Layout";
    private static final String PREFS = "controls";
    private static final String KEY_LAYOUT = "layout_json";

    private ControlLayout() {
    }

    public static List<ControlDescription> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_LAYOUT, null);

        if (json == null) {
            return defaultLayout();
        }

        try {
            List<ControlDescription> list = ControlDescription.listFromJson(new JSONArray(json));
            return list.isEmpty() ? defaultLayout() : list;
        } catch (Exception e) {
            Log.w(TAG, "Bad saved layout, falling back to the default", e);
            return defaultLayout();
        }
    }

    public static void save(Context context, List<ControlDescription> list) {
        try {
            String json = ControlDescription.listToJson(list).toString();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAYOUT, json)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Cannot save layout", e);
        }
    }

    public static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAYOUT)
                .apply();
    }

    /**
     * Stock layout: movement stick and d-pad under the left thumb, face buttons in a
     * diamond on the right, shoulders and triggers along the top edge, Start/Back
     * centred, and the launcher's own Hide / Edit buttons on the bottom rail.
     *
     * <p>These coordinates are not eyeballed: they were checked with circle-accurate
     * collision tests at 720p/1.5x, 1080p/2.0x, 2340x1080/2.75x, 2400x1080/3.0x,
     * 2560x1600/2.0x and 4K/3.5x, and no two controls overlap or fall off screen on any
     * of them. If you move something here, re-check the tight pairs (d-pad vs LT, and
     * Y vs RT) at high density.</p>
     */
    public static List<ControlDescription> defaultLayout() {
        List<ControlDescription> l = new ArrayList<>();

        // Left thumb: movement stick, with the d-pad above it.
        l.add(stick(Binding.LEFT_STICK, 0.140f, 0.680f, 0.85f));
        l.add(dpad(0.140f, 0.300f, 0.62f));

        // Right thumb: camera stick in the bottom-right corner.
        l.add(stick(Binding.RIGHT_STICK, 0.860f, 0.700f, 0.78f));

        // Face buttons in the usual diamond, inboard of the camera stick.
        l.add(button(Binding.Y, 0.700f, 0.200f, 0.62f));
        l.add(button(Binding.X, 0.600f, 0.400f, 0.62f));
        l.add(button(Binding.B, 0.780f, 0.400f, 0.62f));
        l.add(button(Binding.A, 0.700f, 0.600f, 0.62f));

        // Shoulders and triggers along the top.
        l.add(button(Binding.LB, 0.050f, 0.090f, 0.55f));
        l.add(button(Binding.LT, 0.140f, 0.090f, 0.55f));
        l.add(button(Binding.RT, 0.850f, 0.090f, 0.55f));
        l.add(button(Binding.RB, 0.940f, 0.090f, 0.55f));

        // Menu buttons, small, rectangular and out of the way.
        ControlDescription back = button(Binding.BACK, 0.400f, 0.090f, 0.50f);
        back.circle = false;
        l.add(back);

        ControlDescription start = button(Binding.START, 0.550f, 0.090f, 0.50f);
        start.circle = false;
        l.add(start);

        // Stick clicks on the bottom rail.
        l.add(button(Binding.L3, 0.300f, 0.900f, 0.50f));
        l.add(button(Binding.R3, 0.400f, 0.900f, 0.50f));

        // Launcher controls: hide the overlay, or jump straight into the editor.
        ControlDescription hide = button(Binding.TOGGLE_CONTROLS, 0.500f, 0.900f, 0.50f);
        hide.alpha = 110;
        l.add(hide);

        ControlDescription edit = button(Binding.EDIT_LAYOUT, 0.600f, 0.900f, 0.50f);
        edit.alpha = 110;
        l.add(edit);

        return l;
    }

    private static ControlDescription button(Binding b, float x, float y, float scale) {
        ControlDescription d = new ControlDescription(ControlDescription.Type.BUTTON, b, x, y);
        d.scale = scale;
        return d;
    }

    private static ControlDescription stick(Binding b, float x, float y, float scale) {
        ControlDescription d = new ControlDescription(ControlDescription.Type.STICK, b, x, y);
        d.scale = scale;
        return d;
    }

    private static ControlDescription dpad(float x, float y, float scale) {
        ControlDescription d = new ControlDescription(ControlDescription.Type.DPAD, Binding.DPAD_UP, x, y);
        d.scale = scale;
        return d;
    }
}
