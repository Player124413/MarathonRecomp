package com.marathonrecomp.launcher.emu;

import android.content.Context;

import com.marathonrecomp.launcher.LauncherPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the command line and environment for whichever backend the user selected.
 *
 * <p>Everything the two backends disagree about lives here, so {@code GameLauncher} only
 * ever deals with "a command and an environment".</p>
 */
public final class EmulatorEnvironment {

    private final Context context;
    private final LauncherPrefs prefs;
    private final File runtimeDir;

    public EmulatorEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new LauncherPrefs(this.context);
        this.runtimeDir = EmulatorInstaller.runtimeDir(this.context);
    }

    public Emulator emulator() {
        return prefs.emulator();
    }

    /** Absolute path of the selected backend's executable. */
    public File binary() {
        return EmulatorInstaller.binaryFor(context, emulator());
    }

    /** True when the selected backend is actually installed and runnable. */
    public boolean isReady() {
        File bin = binary();
        return bin.isFile() && bin.canExecute();
    }

    /** Directory the x86_64 rootfs libraries live in (both backends can use one). */
    public File rootfsDir() {
        return new File(runtimeDir, "rootfs");
    }

    /**
     * Full argv for launching the game.
     *
     * @param gameBinary the recompiled x86_64 MarathonRecomp executable
     * @param extraArgs  arguments passed through to the game itself
     */
    public List<String> buildCommand(File gameBinary, List<String> extraArgs) {
        List<String> command = new ArrayList<>();

        // Both backends take the guest executable as their first argument; where they
        // differ is the environment (FEX wants FEX_ROOTFS, box64 wants BOX64_LD_LIBRARY_PATH),
        // which buildEnvironment() handles.
        command.add(binary().getAbsolutePath());
        command.add(gameBinary.getAbsolutePath());

        if (extraArgs != null) {
            command.addAll(extraArgs);
        }

        return command;
    }

    /**
     * Environment for the emulator process.
     *
     * @param gameDir working directory of the game (its own libs are added to the search path)
     * @param vpadFile shared memory file the game reads its pad state from
     */
    public Map<String, String> buildEnvironment(File gameDir, File vpadFile) {
        Map<String, String> env = new LinkedHashMap<>();

        File rootfs = rootfsDir();
        File lib = new File(rootfs, "usr/lib/x86_64-linux-gnu");

        // ---- shared, backend independent -------------------------------------------
        final String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("PATH", nativeLibDir + ":/system/bin:" + runtimeDir.getAbsolutePath());

        // The emulator binary is an ordinary ARM64 executable that links against the
        // NDK runtime shipped in the same folder, so bionic has to be able to find it.
        env.put("LD_LIBRARY_PATH", nativeLibDir + ":/system/lib64:/vendor/lib64");

        // Tell the game where to find the launcher's pad.
        env.put("MARATHON_RECOMP_VPAD", vpadFile.getAbsolutePath());

        // Rendering: the game speaks Vulkan, and Turnip/whatever driver the user picked
        // is exposed through the standard loader variables.
        env.put("SDL_VIDEODRIVER", "x11");
        env.put("DISPLAY", ":0");
        env.put("SDL_AUDIODRIVER", "pulseaudio");
        env.put("PULSE_SERVER", "127.0.0.1");

        File icd = new File(runtimeDir, "vulkan/icd.d");

        if (icd.isDirectory()) {
            env.put("VK_ICD_FILENAMES", listJsonFiles(icd));
        }

        if (prefs.isDriverOverrideEnabled()) {
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("GALLIUM_DRIVER", "zink");
            env.put("ZINK_DESCRIPTORS", "lazy");
        }

        // ---- backend specific -------------------------------------------------------
        if (emulator() == Emulator.FEX) {
            // FEX finds its guest libraries through the rootfs; without it the dynamic
            // linker inside the emulated process has nothing to load.
            env.put("FEX_ROOTFS", rootfs.getAbsolutePath());
            env.put("FEX_APP_CONFIG", new File(runtimeDir, "fex").getAbsolutePath());

            // Multi-block + thread-shared code cache: the big win on modern phones.
            env.put("FEX_TSOENABLED", prefs.isFexTsoEnabled() ? "1" : "0");
            env.put("FEX_MULTIBLOCK", "1");
            env.put("FEX_SMCCHECKS", "mman");
            env.put("FEX_MAXINST", "500");

            if (prefs.isEmulatorLoggingEnabled()) {
                env.put("FEX_SILENTLOG", "0");
            } else {
                env.put("FEX_SILENTLOG", "1");
            }
        } else {
            // box64 needs to be pointed at the guest libraries explicitly. The game ships
            // its own x86_64 .so files next to the executable, and an imported rootfs (if
            // the user added one) supplies the rest of the system libraries.
            StringBuilder libPath = new StringBuilder(gameDir.getAbsolutePath());
            libPath.append(':').append(new File(gameDir, "lib").getAbsolutePath());
            libPath.append(':').append(lib.getAbsolutePath());
            libPath.append(':').append(new File(rootfs, "lib/x86_64-linux-gnu").getAbsolutePath());

            // The bundled box64 lives in the APK's native library folder alongside the
            // launcher's own libs; keep that on the host search path so its dependencies
            // (libc++_shared and friends) resolve.
            env.put("BOX64_LD_LIBRARY_PATH", libPath.toString());
            env.put("BOX64_PATH", gameDir.getAbsolutePath());
            env.put("BOX64_LOG", prefs.isEmulatorLoggingEnabled() ? "1" : "0");
            env.put("BOX64_NOBANNER", "1");

            // Dynarec tuning. BIGBLOCK 2 and STRONGMEM 1 are the safe-but-fast defaults;
            // Compatibility mode in Settings drops them back for stubborn devices.
            if (prefs.isCompatibilityMode()) {
                env.put("BOX64_DYNAREC_BIGBLOCK", "0");
                env.put("BOX64_DYNAREC_STRONGMEM", "3");
                env.put("BOX64_DYNAREC_SAFEFLAGS", "2");
                env.put("BOX64_DYNAREC_CALLRET", "0");
            } else {
                env.put("BOX64_DYNAREC_BIGBLOCK", "2");
                env.put("BOX64_DYNAREC_STRONGMEM", "1");
                env.put("BOX64_DYNAREC_SAFEFLAGS", "1");
                env.put("BOX64_DYNAREC_CALLRET", "1");
            }

            env.put("BOX64_DYNAREC_FASTNAN", "0");
            env.put("BOX64_DYNAREC_FASTROUND", "0");
        }

        return env;
    }

    private static String listJsonFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

        if (files == null || files.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (File f : files) {
            if (sb.length() > 0) {
                sb.append(':');
            }

            sb.append(f.getAbsolutePath());
        }

        return sb.toString();
    }
}
