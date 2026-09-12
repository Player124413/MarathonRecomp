package org.libsdl.app;

import android.content.Context;

import java.io.File;

/** Paths shared by the launcher, document provider and native Android storage policy. */
final class AppStorage {
    private AppStorage() {}

    /**
     * App-specific dir under Android/media. Unlike Android/data, on-device file
     * managers can browse it on Android 11+, so it serves as a PC-less fallback
     * for game files and driver imports. Null when external storage is unavailable.
     */
    static File mediaBase(Context context) {
        File[] dirs = context.getExternalMediaDirs();
        return (dirs != null && dirs.length > 0) ? dirs[0] : null;
    }

    /** Mirrors the native GetDataRoot(): internal → Android/data → Android/media, populated wins. */
    static File activeGameRoot(Context context) {
        File internal = new File(context.getFilesDir(), "MarathonRecomp");
        if (new File(internal, "game").isDirectory()) {
            return internal;
        }

        File externalBase = context.getExternalFilesDir(null);
        File external = externalBase != null ? new File(externalBase, "MarathonRecomp") : null;
        if (external != null && new File(external, "game").isDirectory()) {
            return external;
        }

        File media = mediaBase(context);
        if (media != null) {
            File mediaRoot = new File(media, "MarathonRecomp");
            if (new File(mediaRoot, "game").isDirectory()) {
                return mediaRoot;
            }
        }

        return external != null ? external : internal;
    }

    static File configFile(Context context) {
        return new File(activeGameRoot(context), ".config/MarathonRecomp/config.toml");
    }

    /** Where native paths.cpp keeps save data: <game root>/.config/MarathonRecomp/save. */
    static File saveDir(Context context) {
        return new File(activeGameRoot(context), ".config/MarathonRecomp/save");
    }

    static File transferRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    /** Primary import folder; SAF imports and marker files go here. */
    static File driverImportDir(Context context) {
        return new File(transferRoot(context), "driver_import");
    }

    /** All folders the native side scans for dropped drivers, primary first. */
    static File[] driverImportDirs(Context context) {
        File media = mediaBase(context);
        File gameRoot = activeGameRoot(context);
        return media != null
            ? new File[] { driverImportDir(context), new File(media, "driver_import"),
                new File(gameRoot, "driver_import") }
            : new File[] { driverImportDir(context), new File(gameRoot, "driver_import") };
    }

    /**
     * Drop-in folder for a DirectX 12 runtime (an arm64 build of vkd3d). Kept separate from
     * driver_import so a Vulkan ICD is never mistaken for it: the two are loaded by different
     * code (libadrenotools versus dlopen in os/android/vkd3d_android.cpp).
     */
    static File vkd3dImportDir(Context context) {
        return new File(transferRoot(context), "vkd3d_import");
    }

    /**
     * All folders os/android/vkd3d_android.cpp scans for a runtime, in the same order, so the
     * launcher never reports a file the game would not find. The internal names come first
     * because a runtime unpacked by an earlier version lives there.
     */
    static File[] vkd3dImportDirs(Context context) {
        File internal = context.getFilesDir();
        File gameRoot = activeGameRoot(context);
        File media = mediaBase(context);
        java.util.List<File> dirs = new java.util.ArrayList<>();
        dirs.add(new File(internal, "vkd3d"));
        dirs.add(new File(internal, "vkd3d_import"));
        dirs.add(vkd3dImportDir(context));
        if (media != null) dirs.add(new File(media, "vkd3d_import"));
        dirs.add(new File(gameRoot, "vkd3d_import"));
        return dirs.toArray(new File[0]);
    }

    /** Last verdict from the native runtime probe, written at each game start. */
    static File vkd3dStatusFile(Context context) {
        return new File(transferRoot(context), "vkd3d_status.txt");
    }
}
