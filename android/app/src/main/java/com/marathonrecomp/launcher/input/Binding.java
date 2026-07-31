package com.marathonrecomp.launcher.input;

/**
 * Everything an on-screen control can be bound to.
 *
 * <p>The button values are the raw Xbox 360 XInput bit masks, which is exactly what
 * Marathon Recompiled reads out of the shared memory pad (XAMINPUT_GAMEPAD_*), so no
 * translation table is needed anywhere between a finger and the game.</p>
 */
public enum Binding {

    // ---- face buttons -------------------------------------------------------------
    A("A", 0x1000),
    B("B", 0x2000),
    X("X", 0x4000),
    Y("Y", 0x8000),

    // ---- shoulders ----------------------------------------------------------------
    LB("LB", 0x0100),
    RB("RB", 0x0200),

    // ---- d-pad --------------------------------------------------------------------
    DPAD_UP("D-Up", 0x0001),
    DPAD_DOWN("D-Down", 0x0002),
    DPAD_LEFT("D-Left", 0x0004),
    DPAD_RIGHT("D-Right", 0x0008),

    // ---- menu ---------------------------------------------------------------------
    START("Start", 0x0010),
    BACK("Back", 0x0020),

    // ---- stick clicks -------------------------------------------------------------
    L3("L3", 0x0040),
    R3("R3", 0x0080),

    // ---- analog (not a button mask) -----------------------------------------------
    LT("LT", 0),
    RT("RT", 0),
    LEFT_STICK("L-Stick", 0),
    RIGHT_STICK("R-Stick", 0),

    // ---- launcher-only actions -----------------------------------------------------
    /** Hides / shows every other on-screen control. */
    TOGGLE_CONTROLS("Hide", 0),
    /** Opens the in-game layout editor. */
    EDIT_LAYOUT("Edit", 0),
    /** Opens the in-game quick menu (resume / editor / quit). */
    MENU("Menu", 0);

    private final String label;
    private final int mask;

    Binding(String label, int mask) {
        this.label = label;
        this.mask = mask;
    }

    /** Human readable text drawn on the button. */
    public String label() {
        return label;
    }

    /** XInput bit mask, or 0 for analog / launcher-only bindings. */
    public int mask() {
        return mask;
    }

    public boolean isButton() {
        return mask != 0;
    }

    public boolean isTrigger() {
        return this == LT || this == RT;
    }

    public boolean isStick() {
        return this == LEFT_STICK || this == RIGHT_STICK;
    }

    /** Launcher actions never reach the game. */
    public boolean isLauncherAction() {
        return this == TOGGLE_CONTROLS || this == EDIT_LAYOUT || this == MENU;
    }

    public static Binding fromName(String name, Binding fallback) {
        if (name == null) {
            return fallback;
        }

        for (Binding b : values()) {
            if (b.name().equalsIgnoreCase(name)) {
                return b;
            }
        }

        return fallback;
    }

    /** Bindings offered in the editor's "change binding" dialog, in a sensible order. */
    public static Binding[] assignable() {
        return new Binding[] {
                A, B, X, Y,
                LB, RB, LT, RT,
                DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
                START, BACK, L3, R3,
                LEFT_STICK, RIGHT_STICK,
                TOGGLE_CONTROLS, EDIT_LAYOUT, MENU
        };
    }
}
