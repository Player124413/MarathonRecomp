package com.marathonrecomp.launcher.emu;

/**
 * The x86_64 translation layer used to run the recompiled Linux build on an ARM64 phone.
 *
 * <p>Marathon Recompiled is a native x86_64 Linux binary, so something has to translate it.
 * The two backends do the same job with very different trade-offs, and they are also
 * <em>delivered</em> differently, which is the important part:</p>
 *
 * <ul>
 *   <li>{@link #BOX64} — <b>built into the APK</b>. box64 officially supports Android, so it
 *       is compiled from the pinned submodule as part of the app build and shipped inside
 *       the native library folder. Nothing to install: it works the moment the app does.</li>
 *   <li>{@link #FEX} — <b>imported by the user</b>. FEX-Emu upstream does not support
 *       Android and explicitly states it never will (its build system has no Android
 *       target and it expects a glibc userspace), so a working FEX has to come from an
 *       unofficial port. The launcher therefore keeps FEX as an optional runtime the user
 *       supplies.</li>
 * </ul>
 */
public enum Emulator {

    /** Bundled with the app; {@link #binaryName} is the packaged native library. */
    BOX64("box64", "libbox64.so", "Box64", true),

    /** User-supplied; the executable is looked up inside the imported runtime. */
    FEX("fex", "FEXInterpreter", "FEX-Emu", false);

    /** Value persisted in SharedPreferences; keep stable. */
    public final String id;

    /**
     * File name of the executable.
     *
     * <p>For box64 this is {@code libbox64.so}: Android only extracts, and only allows
     * execution of, files matching {@code lib*.so} from the APK's native library folder.
     * It is still an ordinary ARM64 executable — only the name is unusual.</p>
     */
    public final String binaryName;

    /** Human readable name shown in the UI. */
    public final String displayName;

    /** True when the backend ships inside the APK and needs no user action. */
    public final boolean bundled;

    Emulator(String id, String binaryName, String displayName, boolean bundled) {
        this.id = id;
        this.binaryName = binaryName;
        this.displayName = displayName;
        this.bundled = bundled;
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
