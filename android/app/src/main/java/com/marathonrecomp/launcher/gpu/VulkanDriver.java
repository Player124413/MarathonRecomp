package com.marathonrecomp.launcher.gpu;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turnip / custom Vulkan driver support.
 *
 * <p>Marathon Recompiled renders with Vulkan. On Android the system driver reached through
 * box64 is often the weak link: Qualcomm's proprietary blob is only exposed to native
 * Android apps and behaves poorly (or not at all) for an emulated x86_64 Linux process.
 * <a href="https://docs.mesa3d.org/drivers/freedreno.html">Turnip</a> — Mesa's open-source
 * Vulkan driver for Adreno — is the usual answer, and is what every launcher of this kind
 * ends up shipping or importing.</p>
 *
 * <p>The driver is plugged in the standard way, through the Vulkan loader's ICD mechanism:
 * a small JSON manifest points at the driver's {@code .so}, and {@code VK_ICD_FILENAMES}
 * points at the manifest. No linker tricks are needed here, because the game runs as its
 * own process under box64 rather than inside the app's address space.</p>
 *
 * <p>Turnip is <b>Adreno-only</b>. On Mali, Xclipse or PowerVR it must not be forced on;
 * {@link GpuInfo} is used to warn about exactly that.</p>
 */
public final class VulkanDriver {

    private static final String TAG = "MarathonDroid/Vulkan";

    /** Driver files live here; the ICD manifest is generated next to them. */
    private static final String DRIVER_SUBDIR = "vulkan";
    private static final String ICD_NAME = "driver_icd.json";

    /** Loader manifest version understood by every Vulkan loader we care about. */
    private static final String ICD_API_VERSION = "1.3.0";

    private VulkanDriver() {
    }

    public static File driverDir(Context context) {
        File dir = new File(context.getFilesDir(), DRIVER_SUBDIR);

        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create " + dir);
        }

        return dir;
    }

    public static File icdFile(Context context) {
        return new File(driverDir(context), ICD_NAME);
    }

    /** The imported driver library, or null when none is installed. */
    public static File installedDriver(Context context) {
        File dir = driverDir(context);
        File[] libs = dir.listFiles((d, name) -> name.endsWith(".so"));

        if (libs == null || libs.length == 0) {
            return null;
        }

        // Prefer a Freedreno/Turnip library if several were imported.
        for (File lib : libs) {
            if (lib.getName().contains("freedreno") || lib.getName().contains("turnip")) {
                return lib;
            }
        }

        return libs[0];
    }

    public static boolean isInstalled(Context context) {
        return installedDriver(context) != null && icdFile(context).isFile();
    }

    /** Human readable name of the installed driver, for the UI. */
    public static String installedName(Context context) {
        File driver = installedDriver(context);
        return driver != null ? driver.getName() : null;
    }

    /**
     * Imports a driver.
     *
     * <p>Accepts either a bare {@code .so} or an AdrenoTools-style {@code .zip} (as
     * published by the Turnip CI builds), which holds the library plus a {@code meta.json}
     * describing it.</p>
     *
     * @param fileName name of the picked file, used to tell a zip from an so
     * @return null on success, otherwise a human readable reason
     */
    public static String install(Context context, String fileName, InputStream in) {
        File dir = driverDir(context);

        // Only one driver at a time: a stale .so would make the ICD ambiguous.
        clear(context);

        try {
            if (fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                if (!installFromZip(dir, in)) {
                    return "The archive contains no Vulkan driver (.so).";
                }
            } else {
                String name = (fileName == null || !fileName.endsWith(".so"))
                        ? "libvulkan_freedreno.so"
                        : new File(fileName).getName();

                copy(in, new File(dir, name));
            }
        } catch (IOException e) {
            Log.e(TAG, "Driver import failed", e);
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }

        File driver = installedDriver(context);

        if (driver == null) {
            return "No driver library was found in the import.";
        }

        try {
            writeIcd(context, driver);
        } catch (Exception e) {
            Log.e(TAG, "Cannot write the ICD manifest", e);
            return "Cannot write the loader manifest: " + e.getMessage();
        }

        Log.i(TAG, "Installed Vulkan driver " + driver.getName());
        return null;
    }

    /** Removes the imported driver, falling back to the system one. */
    public static void clear(Context context) {
        File dir = driverDir(context);
        File[] files = dir.listFiles();

        if (files == null) {
            return;
        }

        for (File f : files) {
            if (!f.delete()) {
                Log.w(TAG, "Cannot delete " + f);
            }
        }
    }

    // ----------------------------------------------------------------- internals ----

    private static boolean installFromZip(File dir, InputStream in) throws IOException {
        boolean found = false;
        String canonicalDir = dir.getCanonicalPath();

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                // Flatten: AdrenoTools packages keep the library at the top level, but be
                // tolerant of one that nests it.
                String base = new File(entry.getName()).getName();

                if (!base.endsWith(".so")) {
                    continue;
                }

                File out = new File(dir, base);

                if (!out.getCanonicalPath().startsWith(canonicalDir + File.separator)) {
                    throw new IOException("Unsafe entry: " + entry.getName());
                }

                copy(zis, out);
                found = true;
            }
        }

        return found;
    }

    /**
     * Writes the Vulkan loader ICD manifest that points at the driver.
     *
     * <p>This is the documented way to add a driver, so it works with the loader inside the
     * emulated environment without patching anything.</p>
     */
    private static void writeIcd(Context context, File driver) throws Exception {
        JSONObject icd = new JSONObject();
        icd.put("library_path", driver.getAbsolutePath());
        icd.put("api_version", ICD_API_VERSION);

        JSONObject root = new JSONObject();
        root.put("file_format_version", "1.0.0");
        root.put("ICD", icd);

        try (OutputStream os = new FileOutputStream(icdFile(context))) {
            os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
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
}
