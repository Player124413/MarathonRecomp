package com.marathonrecomp.launcher.emu;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Where the emulator backends live on disk, and how a user-supplied package gets unpacked.
 *
 * <p>Neither box64 nor FEX is bundled in the APK: they are separate GPL projects with their
 * own release cadence, and shipping a stale copy helps nobody. The launcher instead expects
 * a runtime package (a zip containing the backend binary and an x86_64 rootfs) to be
 * imported once from Settings, and installs it under:</p>
 *
 * <pre>
 *   filesDir/runtime/box64/box64
 *   filesDir/runtime/fex/FEXInterpreter
 *   filesDir/runtime/rootfs/...
 * </pre>
 */
public final class EmulatorInstaller {

    private static final String TAG = "MarathonDroid/Runtime";

    private EmulatorInstaller() {
    }

    public static File runtimeDir(Context context) {
        File dir = new File(context.getFilesDir(), "runtime");

        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create " + dir);
        }

        return dir;
    }

    public static File dirFor(Context context, Emulator emulator) {
        return new File(runtimeDir(context), emulator.id);
    }

    public static File binaryFor(Context context, Emulator emulator) {
        return new File(dirFor(context, emulator), emulator.binaryName);
    }

    public static boolean isInstalled(Context context, Emulator emulator) {
        File bin = binaryFor(context, emulator);
        return bin.isFile() && bin.length() > 0;
    }

    /**
     * Unpacks a runtime zip for the given backend.
     *
     * @return true when the backend's executable was found and made runnable
     */
    public static boolean installFromZip(Context context, Emulator emulator, InputStream zipStream)
            throws IOException {
        File target = dirFor(context, emulator);

        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }

        String canonicalTarget = target.getCanonicalPath();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(target, entry.getName());

                // Zip-slip guard: never let an entry escape the runtime directory.
                if (!out.getCanonicalPath().startsWith(canonicalTarget + File.separator)
                        && !out.getCanonicalPath().equals(canonicalTarget)) {
                    throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("Cannot create " + out);
                    }

                    continue;
                }

                File parent = out.getParentFile();

                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent);
                }

                copy(zis, out);
            }
        }

        File bin = binaryFor(context, emulator);

        // Some packages nest everything one level deep; find the binary if it moved.
        if (!bin.isFile()) {
            File found = findByName(target, emulator.binaryName, 4);

            if (found != null && !found.equals(bin)) {
                if (!found.renameTo(bin)) {
                    Log.w(TAG, "Found " + found + " but cannot move it to " + bin);
                    bin = found;
                }
            }
        }

        if (!bin.isFile()) {
            Log.e(TAG, "Runtime package has no " + emulator.binaryName);
            return false;
        }

        if (!bin.setExecutable(true, false)) {
            Log.w(TAG, "Cannot mark " + bin + " executable");
        }

        // Anything under bin/ or lib/ that looks like a helper also needs +x.
        makeTreeExecutable(new File(target, "bin"));

        return true;
    }

    /** Deletes an installed backend (Settings -> remove). */
    public static void uninstall(Context context, Emulator emulator) {
        deleteRecursively(dirFor(context, emulator));
    }

    // ----------------------------------------------------------------- helpers ----

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            // Only a -1 means EOF; a 0-byte read is legal and used to truncate files.
            while ((read = in.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
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

    private static void makeTreeExecutable(File dir) {
        if (!dir.isDirectory()) {
            return;
        }

        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                makeTreeExecutable(child);
            } else {
                child.setExecutable(true, false);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Cannot delete " + file);
        }
    }
}
