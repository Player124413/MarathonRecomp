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
 * The x86_64 system libraries the emulated game links against.
 *
 * <p>Marathon Recompiled's Linux build is an ordinary <b>dynamically linked</b> program.
 * Most of its dependencies are static (SDL, curl, fmt, ...), but it still needs the
 * x86_64 versions of the C library and the desktop libraries it links on Linux —
 * {@code libc}, {@code libm}, {@code libdl}, {@code libpthread}, {@code libX11},
 * {@code libglib-2.0}, {@code libgio-2.0} and their dependencies.</p>
 *
 * <p>Android supplies none of those: its own libraries are ARM64 and bionic, not glibc.
 * When they are missing box64 stops with</p>
 *
 * <pre>Error: Loading needed libs in elf ...</pre>
 *
 * <p>and exits with status <b>255</b>, which is the single most common first-run failure.</p>
 *
 * <p>So a small x86_64 rootfs has to be imported once. It is unpacked to
 * {@code filesDir/runtime/rootfs} and handed to box64 through
 * {@code BOX64_LD_LIBRARY_PATH} (and to FEX through {@code FEX_ROOTFS}).</p>
 */
public final class RootfsInstaller {

    private static final String TAG = "MarathonDroid/Rootfs";

    /** Directories a usable rootfs is expected to provide libraries in. */
    private static final String[] LIB_DIRS = {
            "lib/x86_64-linux-gnu",
            "usr/lib/x86_64-linux-gnu",
            "lib64",
            "usr/lib64",
            "lib",
            "usr/lib"
    };

    private RootfsInstaller() {
    }

    public static File rootfsDir(Context context) {
        return new File(EmulatorInstaller.runtimeDir(context), "rootfs");
    }

    /** True when something that looks like a usable rootfs is installed. */
    public static boolean isInstalled(Context context) {
        return findLibc(context) != null;
    }

    /** Locates libc inside the rootfs; its absence means the rootfs is unusable. */
    public static File findLibc(Context context) {
        File root = rootfsDir(context);

        if (!root.isDirectory()) {
            return null;
        }

        for (String dir : LIB_DIRS) {
            File candidate = new File(new File(root, dir), "libc.so.6");

            if (candidate.isFile()) {
                return candidate;
            }
        }

        return null;
    }

    /** Every library directory that actually exists, for BOX64_LD_LIBRARY_PATH. */
    public static String libraryPath(Context context) {
        File root = rootfsDir(context);
        StringBuilder sb = new StringBuilder();

        for (String dir : LIB_DIRS) {
            File candidate = new File(root, dir);

            if (candidate.isDirectory()) {
                if (sb.length() > 0) {
                    sb.append(':');
                }

                sb.append(candidate.getAbsolutePath());
            }
        }

        return sb.toString();
    }

    /** Short description for the UI, e.g. "rootfs installed (312 MB)". */
    public static long sizeBytes(Context context) {
        return sizeOf(rootfsDir(context));
    }

    /**
     * Unpacks a rootfs archive.
     *
     * <p>Accepts a plain zip. Tar archives are deliberately <em>not</em> handled here:
     * most published rootfs images are {@code .tar.xz}, which needs an XZ decoder the
     * platform does not provide, so the UI asks for a zip instead of failing obscurely.</p>
     *
     * @return null on success, otherwise a human readable reason
     */
    public static String install(Context context, InputStream zipStream) {
        File target = rootfsDir(context);
        File staging = new File(target.getParentFile(), "rootfs.tmp");

        deleteRecursively(staging);

        if (!staging.mkdirs()) {
            return "Cannot create " + staging;
        }

        try {
            String canonicalStaging = staging.getCanonicalPath();
            int files = 0;

            try (ZipInputStream zis = new ZipInputStream(zipStream)) {
                ZipEntry entry;

                while ((entry = zis.getNextEntry()) != null) {
                    File out = new File(staging, entry.getName());
                    String canonical = out.getCanonicalPath();

                    if (!canonical.equals(canonicalStaging)
                            && !canonical.startsWith(canonicalStaging + File.separator)) {
                        return "Unsafe entry in the archive: " + entry.getName();
                    }

                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) {
                            return "Cannot create " + out;
                        }

                        continue;
                    }

                    File parent = out.getParentFile();

                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        return "Cannot create " + parent;
                    }

                    copy(zis, out);
                    files++;
                }
            }

            if (files == 0) {
                return "The archive is empty.";
            }

            // Tolerate an archive wrapped in a single top-level folder.
            File root = unwrapSingleDirectory(staging);

            deleteRecursively(target);

            if (!root.renameTo(target)) {
                return "Cannot move the rootfs into place.";
            }

            deleteRecursively(staging);

            if (findLibc(context) == null) {
                return "No libc.so.6 found — this does not look like an x86_64 rootfs.";
            }

            Log.i(TAG, "Installed rootfs with " + files + " files");
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Rootfs install failed", e);
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            deleteRecursively(staging);
        }
    }

    public static void uninstall(Context context) {
        deleteRecursively(rootfsDir(context));
    }

    // ----------------------------------------------------------------- helpers ----

    private static File unwrapSingleDirectory(File dir) {
        File[] children = dir.listFiles();

        if (children != null && children.length == 1 && children[0].isDirectory()) {
            // Only unwrap when the inner folder is not itself a library root.
            File inner = children[0];
            boolean looksLikeRoot = new File(inner, "lib").isDirectory()
                    || new File(inner, "usr").isDirectory();

            if (looksLikeRoot) {
                return inner;
            }
        }

        return dir;
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }

        if (file.isFile()) {
            return file.length();
        }

        File[] children = file.listFiles();
        long total = 0;

        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }

        return total;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (!file.delete()) {
            Log.w(TAG, "Cannot delete " + file);
        }
    }
}
