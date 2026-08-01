package com.marathonrecomp.launcher.emu;

import android.content.Context;
import android.util.Log;

import com.marathonrecomp.launcher.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and installs the x86_64 system libraries automatically.
 *
 * <p>Making the user find, repackage and import a rootfs was the worst part of the setup,
 * so the launcher does it itself: one tap, one download, done.</p>
 *
 * <h3>Why the archive is prepared in CI</h3>
 *
 * <p>Assembling a rootfs on the phone is not realistic. Resolving a package's real
 * download URL needs the archive index (the file name embeds a version that changes with
 * every security update), the dependency tree has to be resolved properly, and modern
 * {@code .deb} files compress their payload with <b>xz</b>, which Android has no decoder
 * for. All of that is solved once by {@code ci/workflows/build-rootfs.yml}, which runs
 * {@code debootstrap} on a real Ubuntu machine and publishes a single stripped
 * <b>{@code .tar.gz}</b>.</p>
 *
 * <p>gzip is the deliberate part: {@link GZIPInputStream} is built into the platform, so
 * together with {@link TarExtractor} the app unpacks the rootfs with no third-party
 * dependency whatsoever. The stream goes network → gunzip → tar directly, so the archive
 * is never stored on disk as a whole.</p>
 */
public final class RootfsDownloader {

    private static final String TAG = "MarathonDroid/Download";

    /** Release tag the rootfs is published under (see the CI workflow). */
    private static final String ROOTFS_TAG = "rootfs-v1";

    private static final String ROOTFS_ASSET = "marathon-rootfs-amd64.tar.gz";

    /** Roughly what the stripped Ubuntu 22.04 base compresses to; used before the server replies. */
    private static final long ROOTFS_APPROX_BYTES = 45L * 1024 * 1024;

    public interface ProgressListener {
        /**
         * @param stage      short description shown to the user
         * @param bytesSoFar bytes handled so far
         * @param totalBytes expected total, -1 when the server did not say
         */
        void onProgress(String stage, long bytesSoFar, long totalBytes);
    }

    private RootfsDownloader() {
    }

    /**
     * Where the rootfs is fetched from.
     *
     * <p>Built from the repository the app was compiled from, so a fork automatically
     * points at its own release rather than the original.</p>
     */
    public static String rootfsUrl() {
        return "https://github.com/" + BuildConfig.SOURCE_REPOSITORY
                + "/releases/download/" + ROOTFS_TAG + "/" + ROOTFS_ASSET;
    }

    public static long approximateSizeBytes() {
        return ROOTFS_APPROX_BYTES;
    }

    /**
     * Downloads the rootfs and unpacks it into {@code filesDir/runtime/rootfs}.
     *
     * <p>Everything lands in a staging directory first and is only swapped into place once
     * {@code libc.so.6} is confirmed present, so an interrupted download can never leave a
     * half-installed rootfs that looks valid.</p>
     *
     * @return null on success, otherwise a human readable reason
     */
    public static String downloadAndInstall(Context context, ProgressListener listener) {
        File target = RootfsInstaller.rootfsDir(context);
        File staging = new File(target.getParentFile(), "rootfs.dl");

        deleteRecursively(staging);

        if (!staging.mkdirs()) {
            return "Cannot create " + staging;
        }

        HttpURLConnection connection = null;

        try {
            report(listener, "Connecting…", 0, ROOTFS_APPROX_BYTES);

            connection = open(rootfsUrl());
            int status = connection.getResponseCode();

            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return "The system libraries have not been published for this build yet "
                        + "(release \"" + ROOTFS_TAG + "\" is missing). "
                        + "Run the \"Build the x86_64 rootfs\" workflow once, or import an "
                        + "archive manually.";
            }

            if (status != HttpURLConnection.HTTP_OK) {
                return "Download failed (HTTP " + status + ")";
            }

            final long total = connection.getContentLengthLong();

            try (InputStream network = connection.getInputStream();
                 CountingInputStream counting =
                         new CountingInputStream(network, listener, total);
                 GZIPInputStream gz = new GZIPInputStream(counting, 64 * 1024)) {

                TarExtractor.extract(gz, staging, (name, bytes) -> { });
            }

            // Only trust it once the loader and the C library are actually there.
            if (!hasLibc(staging)) {
                return "The download did not contain libc — it may have been interrupted.";
            }

            report(listener, "Finishing…", 1, 1);

            deleteRecursively(target);

            if (!staging.renameTo(target)) {
                return "Cannot move the system libraries into place.";
            }

            Log.i(TAG, "Rootfs installed to " + target);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Rootfs download failed", e);

            String message = e.getMessage();

            return "Download failed: " + (message != null ? message : e.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }

            deleteRecursively(staging);
        }
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);   // GitHub redirects to its CDN
        connection.setRequestProperty("User-Agent", "MarathonDroid");
        return connection;
    }

    private static boolean hasLibc(File root) {
        for (String dir : new String[] { "lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu",
                                         "lib64", "usr/lib64" }) {
            if (new File(new File(root, dir), "libc.so.6").exists()) {
                return true;
            }
        }

        return false;
    }

    private static void report(ProgressListener listener, String stage, long done, long total) {
        if (listener != null) {
            listener.onProgress(stage, done, total);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }

        // A dangling symlink is not "exists()" but still needs deleting, so try regardless.
        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        file.delete();
    }

    /** Wraps the network stream to report download progress. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final ProgressListener listener;
        private final long total;

        private long count;
        private long lastReported;

        CountingInputStream(InputStream delegate, ProgressListener listener, long total) {
            this.delegate = delegate;
            this.listener = listener;
            this.total = total;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();

            if (value >= 0) {
                advance(1);
            }

            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);

            if (read > 0) {
                advance(read);
            }

            return read;
        }

        private void advance(int bytes) {
            count += bytes;

            // At most one update per 512 KB, so the UI thread is not flooded.
            if (listener != null && count - lastReported > 512 * 1024) {
                lastReported = count;
                listener.onProgress("Downloading the system libraries…", count, total);
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
