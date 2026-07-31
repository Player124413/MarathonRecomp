package com.marathonrecomp.launcher.input;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serializable description of one on-screen control.
 *
 * <p>Positions are stored as fractions of the screen (0..1) so a layout made on a phone
 * still lands correctly on a tablet, and survives rotation. Size is a multiplier applied
 * to the element's base dp size, which is what the editor's resize slider changes.</p>
 */
public final class ControlDescription {

    public enum Type { BUTTON, STICK, DPAD }

    public Type type = Type.BUTTON;

    /** Centre of the element, as a fraction of the view (0..1). */
    public float x = 0.5f;
    public float y = 0.5f;

    /** Size multiplier applied to the base dp size (0.5 .. 2.5). */
    public float scale = 1.0f;

    /** Opacity of the control, 0..255. */
    public int alpha = 150;

    /** Round or rectangular buttons; ignored for sticks and d-pads. */
    public boolean circle = true;

    /** Latch instead of hold; ignored for sticks and d-pads. */
    public boolean toggle = false;

    /** What the control does. Sticks use LEFT_STICK / RIGHT_STICK. */
    public Binding binding = Binding.A;

    /** Optional label override; null means "use the binding's label". */
    public String text = null;

    public ControlDescription() {
    }

    public ControlDescription(Type type, Binding binding, float x, float y) {
        this.type = type;
        this.binding = binding;
        this.x = x;
        this.y = y;
    }

    public String label() {
        return text != null ? text : binding.label();
    }

    public ControlDescription copy() {
        ControlDescription d = new ControlDescription();
        d.type = type;
        d.x = x;
        d.y = y;
        d.scale = scale;
        d.alpha = alpha;
        d.circle = circle;
        d.toggle = toggle;
        d.binding = binding;
        d.text = text;
        return d;
    }

    // ---------------------------------------------------------------- json ----

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type.name());
        o.put("x", (double) x);
        o.put("y", (double) y);
        o.put("scale", (double) scale);
        o.put("alpha", alpha);
        o.put("circle", circle);
        o.put("toggle", toggle);
        o.put("binding", binding.name());

        if (text != null) {
            o.put("text", text);
        }

        return o;
    }

    public static ControlDescription fromJson(JSONObject o) {
        ControlDescription d = new ControlDescription();

        try {
            d.type = Type.valueOf(o.optString("type", "BUTTON").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            d.type = Type.BUTTON;
        }

        d.x = (float) o.optDouble("x", 0.5);
        d.y = (float) o.optDouble("y", 0.5);
        d.scale = (float) o.optDouble("scale", 1.0);
        d.alpha = o.optInt("alpha", 150);
        d.circle = o.optBoolean("circle", true);
        d.toggle = o.optBoolean("toggle", false);
        d.binding = Binding.fromName(o.optString("binding", "A"), Binding.A);
        d.text = o.has("text") ? o.optString("text", null) : null;

        return d;
    }

    public static JSONArray listToJson(java.util.List<ControlDescription> list) throws JSONException {
        JSONArray arr = new JSONArray();

        for (ControlDescription d : list) {
            arr.put(d.toJson());
        }

        return arr;
    }

    public static java.util.List<ControlDescription> listFromJson(JSONArray arr) {
        java.util.List<ControlDescription> list = new java.util.ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);

            if (o != null) {
                list.add(fromJson(o));
            }
        }

        return list;
    }
}
