package com.marathonrecomp.launcher.emu;

/**
 * The x86_64 translation layer used to run the recompiled Linux build on an ARM64 phone.
 *
 * <p>Marathon Recompiled is a native x86_64 Linux binary, so something has to translate it.
 * Both supported backends do the same job with different trade-offs, and the user picks
 * one in Settings:</p>
 *
 * <ul>
 *   <li>{@link #BOX64} - the widely used, very compatible choice. Slower on heavy code,
 *       but it starts on almost anything and has good SSE coverage.</li>
 *   <li>{@link #FEX} - FEX-Emu. Usually noticeably faster thanks to its JIT and
 *       its thunking, but it wants a proper x86_64 rootfs and a recent kernel.</li>
 * </ul>
 */
public enum Emulator {

    BOX64("box64", "box64", "Box64"),
    FEX("fex", "FEXInterpreter", "FEX-Emu");

    /** Value persisted in SharedPreferences; keep stable. */
    public final String id;

    /** Name of the executable inside the emulator's install directory. */
    public final String binaryName;

    /** Human readable name shown in the UI. */
    public final String displayName;

    Emulator(String id, String binaryName, String displayName) {
        this.id = id;
        this.binaryName = binaryName;
        this.displayName = displayName;
    }

    public static Emulator fromId(String id) {
        for (Emulator e : values()) {
            if (e.id.equalsIgnoreCase(id)) {
                return e;
            }
        }

        return BOX64;
    }
}
