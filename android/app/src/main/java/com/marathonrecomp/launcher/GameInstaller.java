package com.marathonrecomp.launcher;

import android.content.Context;
import android.util.Log;

import com.marathonrecomp.launcher.emu.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a zipped Linux build of Marathon Recompiled into the app's own storage.
 *
 * <p>This is deliberately allowed to land in {@code filesDir}, which at first glance looks
 * wrong given Android's W^X rule. It is fine because <b>the game is never exec()'d</b>:
 * box64 opens it with {@code fopen(..., "rb")} and maps the segments itself (see
 * {@code box64/src/core.c}), so the guest binary needs no execute permission and no
 * exec-friendly location. Only box64 itself must live in the APK's native library
 * directory, and it does.</p>
 *
 * <p>That in turn removes the old restriction that the game had to sit on primary shared
 * storage for the emulator to reach it.</p>
 */
public final class GameInstaller {

    private static final String TAG = "MarathonDroid/Install";

    /** Everything is unpacked under filesDir/game. */
    public static final String GAME_SUBDIR = "game";

    public interface ProgressListener {
        /**
         * @param entryName  file currently being written
         * @param bytesSoFar total bytes written so far
         */
        void onProgress(String entryName, long bytesSoFar);
    }

    /** Outcome of an install attempt. */
    public static final class Result {
        public final boolean success;
        public final String gameDir;
        public final String error;
        public final int fileCount;
        public final long totalBytes;

        Result(boolean success, String gameDir, String error, int fileCount, long totalBytes) {
            this.success = success;
            this.gameDir = gameDir;
            this.error = error;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }

        static Result failure(String error) {
            return new Result(false, null, error, 0, 0);
        }
    }

    private GameInstaller() {
    }

    public static File gameDir(Context context) {
        return new File(context.getFilesDir(), GAME_SUBDIR);
    }

    /** True when a usable game is already installed in the app's own storage. */
    public static boolean isInstalled(Context context) {
        return new File(gameDir(context), GameLauncher.GAME_BINARY).isFile();
    }

    /**
     * Marathon Recompiled is only half of the picture: next to the executable it needs the
     * converted game data at {@code game/default.xex}, produced by its own installer wizard
     * from a legally owned copy of the Xbox 360 game.
     *
     * <p>Without it the game boots straight into that wizard, which expects a desktop file
     * picker and is not usable on a phone. Detecting the situation here lets the launcher
     * explain it instead of dumping the user into a dead end.</p>
     *
     * @param dir the folder holding the MarathonRecomp executable
     */
    public static boolean hasGameData(File dir) {
        return new File(new File(dir, "game"), "default.xex").isFile();
    }

    /**
     * Extracts a zip into {@code filesDir/game}, replacing whatever was there.
     *
     * <p>Handles the usual shapes a release archive comes in: the executable at the top
     * level, or everything nested inside a single wrapper folder (which gets flattened so
     * the game's data ends up beside the binary, where it expects to be).</p>
     *
     * <p>Call this off the UI thread.</p>
     */
    public static Result install(Context context, InputStream zipStream, ProgressListener listener) {
        File target = gameDir(context);

        // A half-finished previous attempt must not be mistaken for a good install.
        File staging = new File(context.getFilesDir(), GAME_SUBDIR + ".tmp");
        com.marathonrecomp.launcher.emu.FileUtils.FileUtils.deleteRecursively(staging);

        File stagingParent = staging.getParentFile();

        if (stagingParent != null && !stagingParent.exists() && !stagingParent.mkdirs()) {
            return Result.failure("Cannot create " + stagingParent);
        }

        if (!staging.mkdirs() && !staging.isDirectory()) {
            return Result.failure("Cannot create " + staging);
        }

        int files = 0;
        long bytes = 0;

        try {
            String canonicalStaging = staging.getCanonicalPath();

            try (ZipInputStream zis = new ZipInputStream(zipStream)) {
                ZipEntry entry;

                while ((entry = zis.getNextEntry()) != null) {
                    File out = new File(staging, entry.getName());
                    String canonical = out.getCanonicalPath();

                    // Zip-slip guard: never let an entry escape the staging directory.
                    if (!canonical.equals(canonicalStaging)
                            && !canonical.startsWith(canonicalStaging + File.separator)) {
                        return Result.failure("Unsafe entry in the archive: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) {
                            return Result.failure("Cannot create " + out);
                        }

                        continue;
                    }

                    File parent = out.getParentFile();

                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        return Result.failure("Cannot create " + parent);
                    }

                    bytes += copy(zis, out);
                    files++;

                    if (listener != null) {
                        listener.onProgress(entry.getName(), bytes);
                    }
                }
            }

            if (files == 0) {
                return Result.failure("The archive is empty.");
            }

            // Releases are usually zipped as one wrapper folder; flatten it so the binary
            // and its data sit together at the root of the game directory.
            File root = unwrapSingleDirectory(staging);

            File binary = new File(root, GameLauncher.GAME_BINARY);

            if (!binary.isFile()) {
                File found = findByName(root, GameLauncher.GAME_BINARY, 4);

                if (found == null) {
                    return Result.failure(
                            "No \"" + GameLauncher.GAME_BINARY + "\" executable inside the archive.");
                }

                // Found it deeper in the tree: treat that folder as the game root.
                root = found.getParentFile();
                binary = found;
            }

            // Harmless, and keeps the file usable if it is ever copied somewhere exec-friendly.
            if (!binary.setExecutable(true, false)) {
                Log.w(TAG, "Cannot mark " + binary + " executable (not required for box64)");
            }

            // Swap the finished tree into place only once it is known good.
            com.marathonrecomp.launcher.emu.FileUtils.FileUtils.deleteRecursively(target);

            if (!root.renameTo(target)) {
                return Result.failure("Cannot move the installed files into place.");
            }

            com.marathonrecomp.launcher.emu.FileUtils.FileUtils.deleteRecursively(staging);

            Log.i(TAG, "Installed " + files + " files (" + bytes + " bytes) to " + target);

            return new Result(true, target.getAbsolutePath(), null, files, bytes);
        } catch (IOException e) {
            Log.e(TAG, "Install failed", e);
            return Result.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            com.marathonrecomp.launcher.emu.FileUtils.FileUtils.deleteRecursively(staging);
        }
    }

    /** Removes an installed game (Settings → remove). */
    public static void uninstall(Context context) {
        com.marathonrecomp.launcher.emu.FileUtils.FileUtils.deleteRecursively(gameDir(context));
    }

    // ----------------------------------------------------------------- helpers ----

    /**
     * If {@code dir} holds exactly one subdirectory and nothing else, returns that
     * subdirectory; otherwise returns {@code dir} unchanged.
     */
    private static File unwrapSingleDirectory(File dir) {
        File[] children = dir.listFiles();

        if (children != null && children.length == 1 && children[0].isDirectory()) {
            return children[0];
        }

        return dir;
    }

    private static long copy(InputStream in, File out) throws IOException {
        long written = 0;

        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            // Only -1 means EOF; a 0-byte read is legal and must not end the copy early,
            // which would silently truncate a file.
            while ((read = in.read(buffer)) != -1) {
                os.write(buffer, 0, read);
                written += read;
            }
        }

        return written;
    }

    private static File findByName(File dir, String name, int depth) {
        if (depth < 0 || !dir.isDirectory()) {
            return null;
        }

        File[] children = dir.listFiles();

        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (child.isFile() && child.getName().equals(name)) {
                return child;
            }
        }

        for (File child : children) {
            if (child.isDirectory()) {
                File found = findByName(child, name, depth - 1);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

}
