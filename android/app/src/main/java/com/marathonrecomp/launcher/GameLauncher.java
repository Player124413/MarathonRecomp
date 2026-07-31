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

        String gameDirPath = prefs.gameDir();

        if (gameDirPath == null) {
            return context.getString(R.string.error_no_game_dir);
        }

        File gameDir = new File(gameDirPath);
        File gameBinary = new File(gameDir, GAME_BINARY);

        if (!gameBinary.isFile()) {
            return context.getString(R.string.error_game_binary_missing, gameBinary.getAbsolutePath());
        }

        Emulator emulator = environment.emulator();

        if (!environment.isReady()) {
            return context.getString(R.string.error_runtime_missing, emulator.displayName);
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
