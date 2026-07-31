package com.marathonrecomp.launcher;

import android.util.Log;

/**
 * JNI entry points into {@code libmarathondroid.so}.
 *
 * <p>Only two things need native code: the shared-memory pad writer (so touch and gamepad
 * input can reach the emulated game without any input-device plumbing) and a small
 * {@code posix_spawn} helper used to start the emulator with a controlled environment.</p>
 */
public final class NativeBridge {

    private static final String TAG = "MarathonDroid/Native";
    private static boolean loaded;

    private NativeBridge() {
    }

    public static synchronized boolean load() {
        if (loaded) {
            return true;
        }

        try {
            System.loadLibrary("marathondroid");
            loaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot load libmarathondroid.so", t);
        }

        return loaded;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // -------------------------------------------------------------- virtual pad ----

    /** Creates / maps the shared memory file the game reads its pad state from. */
    public static native boolean vpadOpen(String path);

    public static native void vpadClose();

    public static native boolean vpadIsOpen();

    /**
     * Publishes one pad frame.
     *
     * @param slot      controller index, 0 for the only pad the game uses
     * @param connected false makes the game report "no controller"
     * @param source    {@code PadState.SOURCE_*}, informational
     * @param buttons   XInput button mask
     */
    public static native void vpadPush(int slot, boolean connected, int source, int buttons,
                                       int leftTrigger, int rightTrigger,
                                       int lx, int ly, int rx, int ry);

    /**
     * Reads back the rumble the game asked for.
     * Packed as: bits 63..48 counter, 31..16 left motor, 15..0 right motor.
     */
    public static native long vpadPollRumble(int slot);

    // ------------------------------------------------------------------ process ----

    /**
     * Starts a process with an explicit environment and working directory.
     *
     * @param argv    full command line, argv[0] is the executable
     * @param envp    environment as {@code KEY=value} strings
     * @param cwd     working directory
     * @param logPath file the child's stdout and stderr are appended to
     * @return the child's pid, or -1 on failure
     */
    public static native int spawn(String[] argv, String[] envp, String cwd, String logPath);

    /** Returns the exit code once the child is gone, or {@code Integer.MIN_VALUE} if still running. */
    public static native int pollExit(int pid);

    /** Sends SIGTERM, then SIGKILL after a grace period. */
    public static native void terminate(int pid);
}
