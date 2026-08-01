package com.marathonrecomp.launcher.emu;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Minimal POSIX/GNU tar reader.
 *
 * <p>Android ships no tar support, and the obvious alternatives do not fit: {@code .tar.xz}
 * (what most rootfs images use) needs an XZ decoder that is not on the platform, and adding
 * a compression library for one feature is a poor trade. {@code .tar.gz} on the other hand
 * only needs {@link java.util.zip.GZIPInputStream}, which is built in — so the launcher can
 * unpack a rootfs on its own with no third-party dependency at all.</p>
 *
 * <p>Handles what a real rootfs tarball actually contains:</p>
 * <ul>
 *   <li>regular files and directories</li>
 *   <li><b>symlinks</b> — essential, because {@code libc.so.6} is normally a symlink to the
 *       versioned library, and losing them would leave an unusable rootfs</li>
 *   <li>hard links</li>
 *   <li>GNU long names ({@code L}/{@code K}) and PAX extended headers ({@code x}/{@code g})</li>
 *   <li>device/fifo entries, which are skipped rather than treated as errors</li>
 * </ul>
 */
public final class TarExtractor {

    private static final String TAG = "MarathonDroid/Tar";

    private static final int BLOCK = 512;

    public interface ProgressListener {
        /**
         * @param entryName current entry
         * @param bytesRead total compressed-stream bytes consumed so far, -1 if unknown
         */
        void onEntry(String entryName, long bytesRead);
    }

    private TarExtractor() {
    }

    /**
     * Extracts a tar stream into {@code target}.
     *
     * @return the number of entries written
     * @throws IOException on a malformed archive or an entry escaping {@code target}
     */
    public static int extract(InputStream in, File target, ProgressListener listener)
            throws IOException {
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }

        final String canonicalTarget = target.getCanonicalPath();
        final byte[] header = new byte[BLOCK];

        int entries = 0;
        long consumed = 0;

        // Pending metadata from a preceding GNU/PAX header entry.
        String longName = null;
        String longLink = null;

        int emptyBlocks = 0;

        while (true) {
            if (!readFully(in, header, BLOCK)) {
                break;
            }

            consumed += BLOCK;

            if (isAllZero(header)) {
                // Two consecutive zero blocks mark the end of the archive.
                if (++emptyBlocks >= 2) {
                    break;
                }

                continue;
            }

            emptyBlocks = 0;

            String name = parseString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            String linkName = parseString(header, 157, 100);

            // "ustar" archives split long paths into a prefix field.
            String prefix = parseString(header, 345, 155);

            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            if (longName != null) {
                name = longName;
                longName = null;
            }

            if (longLink != null) {
                linkName = longLink;
                longLink = null;
            }

            switch (type) {
                case 'L': {
                    // GNU long name: the following entry's name is this entry's body.
                    longName = readString(in, size);
                    consumed += skipPadding(in, size);
                    continue;
                }

                case 'K': {
                    longLink = readString(in, size);
                    consumed += skipPadding(in, size);
                    continue;
                }

                case 'x':
                case 'g': {
                    // PAX header: parse the "path=" record and ignore the rest.
                    String pax = readString(in, size);
                    consumed += skipPadding(in, size);

                    String paxPath = parsePaxPath(pax);

                    if (paxPath != null) {
                        longName = paxPath;
                    }

                    continue;
                }

                default:
                    break;
            }

            File out = new File(target, name);

            // Path traversal guard: an entry must stay inside the target directory.
            String canonical = out.getCanonicalPath();

            if (!canonical.equals(canonicalTarget)
                    && !canonical.startsWith(canonicalTarget + File.separator)) {
                throw new IOException("Unsafe tar entry: " + name);
            }

            switch (type) {
                case '5': {
                    if (!out.exists() && !out.mkdirs()) {
                        Log.w(TAG, "Cannot create directory " + out);
                    }

                    break;
                }

                case '0':
                case '\0': {
                    File parent = out.getParentFile();

                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Cannot create " + parent);
                    }

                    writeFile(in, out, size);
                    consumed += size;
                    consumed += skipPadding(in, size) - size;

                    // Preserve the executable bit from the tar mode.
                    long mode = parseOctal(header, 100, 8);

                    if ((mode & 0111) != 0 && !out.setExecutable(true, false)) {
                        Log.w(TAG, "Cannot mark " + out + " executable");
                    }

                    entries++;

                    if (listener != null) {
                        listener.onEntry(name, consumed);
                    }

                    break;
                }

                case '2': {
                    // Symlink. Vital for a rootfs: libc.so.6 is usually a link.
                    createSymlink(out, linkName);
                    entries++;
                    break;
                }

                case '1': {
                    // Hard link. A symlink to the same target is close enough here and
                    // avoids duplicating the payload.
                    File existing = new File(target, linkName);

                    if (existing.exists()) {
                        createSymlink(out, existing.getAbsolutePath());
                        entries++;
                    }

                    break;
                }

                default: {
                    // Character/block devices, fifos, sockets: nothing useful to unpack.
                    consumed += skipPadding(in, size);
                    break;
                }
            }
        }

        return entries;
    }

    // ------------------------------------------------------------------ helpers ----

    private static void createSymlink(File link, String targetPath) {
        try {
            File parent = link.getParentFile();

            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }

            // Replace whatever is already there, otherwise a re-extract fails.
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), Paths.get(targetPath));
        } catch (Exception e) {
            // Some filesystems refuse symlinks; a rootfs is still mostly usable without
            // a few of them, so log and continue rather than aborting the whole install.
            Log.w(TAG, "Cannot create symlink " + link + " -> " + targetPath + ": " + e);
        }
    }

    private static void writeFile(InputStream in, File out, long size) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;

            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);

                if (read < 0) {
                    throw new IOException("Truncated tar entry: " + out.getName());
                }

                os.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /** Skips the entry body plus its padding to the next 512-byte boundary. */
    private static long skipPadding(InputStream in, long size) throws IOException {
        long total = size + padding(size);
        long remaining = total;

        while (remaining > 0) {
            long skipped = in.skip(remaining);

            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to reading.
                if (in.read() < 0) {
                    break;
                }

                remaining--;
            } else {
                remaining -= skipped;
            }
        }

        return total;
    }

    private static long padding(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? 0 : BLOCK - rem;
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];

        if (!readFully(in, data, data.length)) {
            throw new IOException("Truncated tar metadata entry");
        }

        return new String(data, StandardCharsets.UTF_8).trim();
    }

    /** Reads exactly {@code length} bytes, tolerating short reads from the gzip stream. */
    private static boolean readFully(InputStream in, byte[] buffer, int length)
            throws IOException {
        int offset = 0;

        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);

            if (read < 0) {
                return offset != 0 && fail(offset, length);
            }

            offset += read;
        }

        return true;
    }

    private static boolean fail(int got, int want) throws IOException {
        throw new IOException("Truncated tar stream (" + got + " of " + want + " bytes)");
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }

        return true;
    }

    private static String parseString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;

        while (end < limit && header[end] != 0) {
            end++;
        }

        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        // GNU base-256 encoding for large values: high bit of the first byte is set.
        if ((header[offset] & 0x80) != 0) {
            long value = 0;

            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xFF);
            }

            return value;
        }

        long value = 0;

        for (int i = offset; i < offset + length; i++) {
            int c = header[i] & 0xFF;

            if (c == 0 || c == ' ') {
                if (value != 0) {
                    break;
                }

                continue;
            }

            if (c < '0' || c > '7') {
                break;
            }

            value = value * 8 + (c - '0');
        }

        return value;
    }

    /** Extracts the {@code path=} record from a PAX header body. */
    private static String parsePaxPath(String pax) {
        // Records look like: "<len> key=value\n"
        for (String line : pax.split("\n")) {
            int space = line.indexOf(' ');

            if (space < 0) {
                continue;
            }

            String record = line.substring(space + 1);

            if (record.startsWith("path=")) {
                return record.substring("path=".length());
            }
        }

        return null;
    }
}
