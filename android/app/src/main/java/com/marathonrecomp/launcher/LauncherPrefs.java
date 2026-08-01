package com.marathonrecomp.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import com.marathonrecomp.launcher.emu.Emulator;

/** Everything the user can toggle, in one place. */
public final class LauncherPrefs {

    private static final String PREFS = "launcher";

    private static final String KEY_EMULATOR = "emulator";
    private static final String KEY_COMPATIBILITY = "compatibility_mode";
    private static final String KEY_EMU_LOGGING = "emulator_logging";
    private static final String KEY_FEX_TSO = "fex_tso";
    private static final String KEY_TURNIP = "turnip_enabled";
    private static final String KEY_HAPTICS = "haptics";
    private static final String KEY_TOUCH_CONTROLS = "touch_controls";
    private static final String KEY_GAME_DIR = "game_dir";
    private static final String KEY_X_DISPLAY = "x_display";
    private static final String KEY_PULSE_SERVER = "pulse_server";

    private final SharedPreferences prefs;

    public LauncherPrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- emulator ----

    public Emulator emulator() {
        return Emulator.fromId(prefs.getString(KEY_EMULATOR, Emulator.BOX64.id));
    }

    public void setEmulator(Emulator emulator) {
        prefs.edit().putString(KEY_EMULATOR, emulator.id).apply();
    }

    /** Safer, slower backend settings for devices that refuse to boot otherwise. */
    public boolean isCompatibilityMode() {
        return prefs.getBoolean(KEY_COMPATIBILITY, false);
    }

    public void setCompatibilityMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_COMPATIBILITY, enabled).apply();
    }

    public boolean isEmulatorLoggingEnabled() {
        return prefs.getBoolean(KEY_EMU_LOGGING, false);
    }

    public void setEmulatorLoggingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_EMU_LOGGING, enabled).apply();
    }

    /**
     * FEX's TSO emulation keeps x86 memory ordering. Correct but costly; phones with
     * hardware TSO support (recent Snapdragons) barely notice, older ones do.
     */
    public boolean isFexTsoEnabled() {
        return prefs.getBoolean(KEY_FEX_TSO, true);
    }

    public void setFexTsoEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FEX_TSO, enabled).apply();
    }

    /**
     * Use the imported Turnip / custom Vulkan driver instead of the system one.
     *
     * <p>On by default: whenever a driver has actually been imported it is almost
     * certainly the reason the user imported it. Ignored when none is installed.</p>
     */
    public boolean isTurnipEnabled() {
        return prefs.getBoolean(KEY_TURNIP, true);
    }

    public void setTurnipEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TURNIP, enabled).apply();
    }

    // ---------------------------------------------------------------- input ----

    public boolean isHapticsEnabled() {
        return prefs.getBoolean(KEY_HAPTICS, true);
    }

    public void setHapticsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply();
    }

    /** Master switch for the on-screen pad (it also hides itself when a real pad appears). */
    public boolean isTouchControlsEnabled() {
        return prefs.getBoolean(KEY_TOUCH_CONTROLS, true);
    }

    public void setTouchControlsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TOUCH_CONTROLS, enabled).apply();
    }

    // ----------------------------------------------------------------- game ----

    // --------------------------------------------------------------- display ----

    /**
     * X display to render to, e.g. {@code :0} for Termux:X11.
     *
     * <p>Empty by default and that is deliberate: Android has no X server, so claiming
     * {@code DISPLAY=:0} when nothing is listening only turns a clear failure into a
     * confusing one. Set it when you actually run Termux:X11 or XServer XSDL.</p>
     */
    public String xServerDisplay() {
        return prefs.getString(KEY_X_DISPLAY, "");
    }

    public void setXServerDisplay(String display) {
        prefs.edit().putString(KEY_X_DISPLAY, display == null ? "" : display.trim()).apply();
    }

    /** PulseAudio server, e.g. {@code 127.0.0.1}. Empty means "no audio". */
    public String pulseServer() {
        return prefs.getString(KEY_PULSE_SERVER, "");
    }

    public void setPulseServer(String server) {
        prefs.edit().putString(KEY_PULSE_SERVER, server == null ? "" : server.trim()).apply();
    }

    // ----------------------------------------------------------------- game ----

    public String gameDir() {
        return prefs.getString(KEY_GAME_DIR, null);
    }

    public void setGameDir(String path) {
        prefs.edit().putString(KEY_GAME_DIR, path).apply();
    }
}
