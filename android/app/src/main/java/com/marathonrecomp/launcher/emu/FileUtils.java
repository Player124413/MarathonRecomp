package com.marathonrecomp.launcher.emu;

import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shared filesystem helpers for the installers. */
public final class FileUtils {

    private static final String TAG = "MarathonDroid/Files";

    private FileUtils() {
    }

    /**
     * Deletes a file or directory tree, symlinks included.
     *
     * <p>Two details make this different from the obvious loop, and both caused a real
     * failure:</p>
     *
     * <ul>
     *   <li>Existence is tested with {@link LinkOption#NOFOLLOW_LINKS}. A rootfs is full
     *       of symlinks, and an interrupted install leaves <b>dangling</b> ones. Plain
     *       {@code File.exists()} reports false for those, so they were skipped, the
     *       staging directory was never emptied, and the next attempt died with
     *       "Cannot create .../rootfs.tmp".</li>
     *   <li>Recursion stops at symlinked directories, so deleting a staging tree can
     *       never follow a link out of it and destroy the target's contents.</li>
     * </ul>
     */
    public static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }

        Path path = file.toPath();

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        boolean symlink = Files.isSymbolicLink(path);

        if (file.isDirectory() && !symlink) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            Log.w(TAG, "Cannot delete " + file + ": " + e);
        }
    }

    /**
     * Creates {@code dir} and its parents.
     *
     * @return null on success, otherwise the path that could not be created
     */
    public static String ensureDirectory(File dir) {
        if (dir == null) {
            return "null";
        }

        if (dir.isDirectory()) {
            return null;
        }

        File parent = dir.getParentFile();

        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return parent.getAbsolutePath();
        }

        // isDirectory() again: mkdirs() returns false when another thread won the race.
        if (!dir.mkdirs() && !dir.isDirectory()) {
            return dir.getAbsolutePath();
        }

        return null;
    }
}
