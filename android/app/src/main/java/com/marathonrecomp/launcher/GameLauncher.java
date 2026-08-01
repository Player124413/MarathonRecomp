package com.marathonrecomp.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.marathonrecomp.launcher.emu.Emulator;
import com.marathonrecomp.launcher.emu.EmulatorEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts and supervises the emulated game process.
 *
 * <p>The launcher does not run the game inside its own address space: it spawns the
 * selected backend (box64 or FEX) as a child process with a purpose-built environment,
 * and talks to the game only through the shared memory pad. That keeps a crash inside
 * the emulator from taking the UI down with it, and makes switching backends a matter of
 * changing one command line.</p>
 */
public final class GameLauncher {

    private static final String TAG = "MarathonDroid/Launcher";

    /** The recompiled game's executable name inside the game directory. */
    public static final String GAME_BINARY = "MarathonRecomp";

    public interface Listener {
        void onStarted();

        /** Called on the main thread once the child is gone. */
        void onExited(int exitCode);

        void onError(String message);
    }

    private final Context context;
    private final LauncherPrefs prefs;
    private final EmulatorEnvironment environment;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Thread watcher;
    private volatile int pid = -1;

    public GameLauncher(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new LauncherPrefs(this.context);
        this.environment = new EmulatorEnvironment(this.context);
    }

    /** The shared memory file both sides use for the pad. */
    public File vpadFile() {
        return new File(context.getFilesDir(), "marathon-vpad.shm");
    }

    public File logFile() {
        return new File(context.getFilesDir(), "logs/game.log");
    }

    public boolean isRunning() {
        return pid > 0;
    }

    /**
     * Last {@code maxLines} lines of the game log.
     *
     * <p>The log holds the emulator's stdout and stderr, which is where the actual reason
     * for a failed launch lives ("Error: Loading needed libs in elf ...", a missing
     * library name, and so on).</p>
     */
    public String readLogTail(int maxLines) {
        File log = logFile();

        if (!log.isFile()) {
            return "";
        }

        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(log),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                lines.addLast(line);

                if (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read the log", e);
            return "";
        }

        return String.join("\n", lines);
    }

    /** Opens a share sheet with the whole log attached, for bug reports. */
    public void shareLog(android.content.Context activityContext) {
        File log = logFile();

        if (!log.isFile()) {
            return;
        }

        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    activityContext, activityContext.getPackageName() + ".fileprovider", log);

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Marathon Droid log");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

            activityContext.startActivity(android.content.Intent.createChooser(intent, "Share the log"));
        } catch (Exception e) {
            Log.e(TAG, "Cannot share the log", e);
        }
    }

    /**
     * Validates the setup and starts the game.
     *
     * @return null when the launch was started, or a human readable reason it could not be
     */
    public String start(Listener listener) {
        if (isRunning()) {
            return context.getString(R.string.error_already_running);
        }

        if (!NativeBridge.load()) {
            return context.getString(R.string.error_native_missing);
        }

        // A game installed from a zip lives in the app's own storage and always wins; the
        // folder picker stays as the alternative for a build already unpacked on the phone.
        File gameDir;

        if (GameInstaller.isInstalled(context)) {
            gameDir = GameInstaller.gameDir(context);
        } else {
            String gameDirPath = prefs.gameDir();

            if (gameDirPath == null) {
                return context.getString(R.string.error_no_game_dir);
            }

            gameDir = new File(gameDirPath);
        }

        File gameBinary = new File(gameDir, GAME_BINARY);

        if (!gameBinary.isFile()) {
            return context.getString(R.string.error_game_binary_missing, gameBinary.getAbsolutePath());
        }

        // The executable alone is not enough: without game/default.xex the game drops into
        // its desktop installer wizard, which cannot be driven on a phone. Say so plainly.
        if (!GameInstaller.hasGameData(gameDir)) {
            return context.getString(R.string.error_game_data_missing, gameDir.getAbsolutePath());
        }

        Emulator emulator = environment.emulator();

        if (!environment.isReady()) {
            File binary = environment.binary();

            // Distinguish "not installed" from "installed but Android will not run it":
            // since Android 10 the app's data directory is mounted no-exec, so an imported
            // runtime is present yet unusable. That deserves its own message.
            if (binary.isFile() && !binary.canExecute()) {
                return context.getString(R.string.error_runtime_not_executable,
                        emulator.displayName, binary.getAbsolutePath());
            }

            return context.getString(R.string.error_runtime_missing, emulator.displayName);
        }

        // Without the x86_64 system libraries the game cannot resolve libc/libX11/glib,
        // and box64 would exit 255 with nothing useful on screen. Catch it up front.
        if (!com.marathonrecomp.launcher.emu.RootfsInstaller.isInstalled(context)) {
            return context.getString(R.string.error_rootfs_missing);
        }

        // The pad must exist before the game maps it, otherwise it starts up padless.
        File vpad = vpadFile();

        if (!NativeBridge.vpadOpen(vpad.getAbsolutePath())) {
            return context.getString(R.string.error_vpad);
        }

        File log = logFile();
        File logDir = log.getParentFile();

        if (logDir != null && !logDir.exists() && !logDir.mkdirs()) {
            Log.w(TAG, "Cannot create " + logDir);
        }

        // Start each run with an empty log; the spawn helper appends, so otherwise the
        // failure dialog would show the previous launch's error.
        if (log.isFile() && !log.delete()) {
            Log.w(TAG, "Cannot clear " + log);
        }

        List<String> command = environment.buildCommand(gameBinary, new ArrayList<>());
        Map<String, String> env = environment.buildEnvironment(gameDir, vpad);

        String[] envp = new String[env.size()];
        int i = 0;

        for (Map.Entry<String, String> entry : env.entrySet()) {
            envp[i++] = entry.getKey() + "=" + entry.getValue();
        }

        Log.i(TAG, "Launching with " + emulator.displayName + ": " + String.join(" ", command));

        int spawned = NativeBridge.spawn(command.toArray(new String[0]), envp,
                gameDir.getAbsolutePath(), log.getAbsolutePath());

        if (spawned <= 0) {
            NativeBridge.vpadClose();
            return context.getString(R.string.error_spawn_failed, emulator.displayName);
        }

        pid = spawned;

        if (listener != null) {
            listener.onStarted();
        }

        startWatcher(listener);
        return null;
    }

    private void startWatcher(Listener listener) {
        watcher = new Thread(() -> {
            int exitCode = 0;

            while (true) {
                int result = NativeBridge.pollExit(pid);

                if (result != Integer.MIN_VALUE) {
                    exitCode = result;
                    break;
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            Log.i(TAG, "Game exited with code " + exitCode);
            pid = -1;
            NativeBridge.vpadClose();

            final int code = exitCode;

            if (listener != null) {
                main.post(() -> listener.onExited(code));
            }
        }, "game-watcher");

        watcher.setDaemon(true);
        watcher.start();
    }

    /** Asks the game to quit, then kills it if it does not. */
    public void stop() {
        int current = pid;

        if (current > 0) {
            NativeBridge.terminate(current);
        }

        pid = -1;
        NativeBridge.vpadClose();
    }
}
