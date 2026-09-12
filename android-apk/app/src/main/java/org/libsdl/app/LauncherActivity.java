package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lightweight pre-flight screen. It deliberately does not load SDL or Vulkan. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_DRIVER = 1001;
    private static final int REQUEST_GAME_ZIP = 1002;
    private static final int REQUEST_GAME_TREE = 1003;
    private static final int REQUEST_GAME_PACKAGES = 1006;
    private static final int DRIVER_IMPORTED = 3;
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String[] DRIVER_VALUES = {
        "Auto", "System", "Bundled", "Imported"
    };
    private static final String[] RENDER_MODE_VALUES = {"Auto", "GMEM", "Sysmem"};
    /** Values of the native [Video] TripleBuffering key; Auto resolves per backend+driver. */
    private static final String[] TRIPLE_BUFFER_VALUES = { "Auto", "On", "Off" };
    private static final int TRIPLE_BUFFER_AUTO = 0;
    private static final int TRIPLE_BUFFER_ON = 1;
    private static final int TRIPLE_BUFFER_OFF = 2;
    /** Values of the native [Video] GraphicsAPI key; index 0 is what every build can run. */
    private static final String[] RENDERER_VALUES = { "Vulkan", "D3D12" };
    private static final int RENDERER_VULKAN = 0;
    private static final int RENDERER_DIRECTX12 = 1;
    /** Must match g_vkd3dLibraryNames in MarathonRecomp/os/android/vkd3d_android.cpp. */
    private static final String[] VKD3D_LIBRARY_NAMES = {
        "libvkd3d_proton.so", "libvkd3d-proton.so", "libvkd3d.so", "libd3d12.so", "d3d12.so"
    };
    private static final String[] DLC_DIRECTORIES = {
        "Additional Episode - Sonic Boss Attack", "Additional Episode - Shadow Boss Attack",
        "Additional Episode - Silver Boss Attack", "Additional Episode - Team Attack Amigo",
        "Additional Mission Pack - Sonic Very Hard", "Additional Mission Pack - Shadow Very Hard",
        "Additional Mission Pack - Silver Very Hard"
    };

    private TextView installStatus;
    private TextView driverStatus;
    private TextView diagnosticsStatus;
    private TextView updateStatus;
    private Button playButton;
    private Button updateButton;
    private Spinner driverSpinner;
    private Spinner renderSpinner;
    private Spinner rendererSpinner;
    private Spinner tripleBufferSpinner;
    private TextView tripleBufferDescription;
    private TextView rendererDescription;
    private TextView vkd3dStatus;
    private CheckBox skipIntro;
    private CheckBox validation;
    private CheckBox gfxCapture;
    private CheckBox forceBc;
    private SharedPreferences prefs;
    private InstallState lastInstallState;

    private static final class InstallState {
        final boolean ready;
        final String message;
        InstallState(boolean ready, String message) {
            this.ready = ready;
            this.message = message;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getPreferences(MODE_PRIVATE);
        setContentView(buildPage());
        loadSettings();
        maybeCheckForUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateManager.resumePendingInstall(this);
        refreshStatuses();
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page);

        TextView title = text(getString(R.string.launcher_title), 28, true);
        page.addView(title);
        TextView subtitle = text(getString(R.string.launcher_subtitle), 15, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(3), 0, dp(14));
        page.addView(subtitle);

        LinearLayout files = card(R.string.launcher_game_files);
        installStatus = statusText();
        files.addView(installStatus);
        files.addView(button(R.string.launcher_install_game, view -> chooseInstallSource()));
        LinearLayout fileButtons = row();
        fileButtons.addView(button(R.string.launcher_open_files, view -> openFiles("game")), weighted());
        fileButtons.addView(button(R.string.launcher_recheck, view -> refreshStatuses()), weighted());
        files.addView(fileButtons);
        files.addView(button(R.string.launcher_open_saves, view -> openFiles("save")));
        page.addView(files);

        LinearLayout updates = card(R.string.launcher_updates);
        updateStatus = statusText();
        updateStatus.setText(getString(R.string.update_current_version, UpdateManager.currentVersion(this)));
        updates.addView(updateStatus);
        updateButton = button(R.string.update_check, view -> checkForUpdates(true));
        updates.addView(updateButton);
        page.addView(updates);

        LinearLayout graphics = collapsibleCard(page, R.string.launcher_graphics, "expand_graphics", false);
        // Renderer first: it decides which API the whole session uses, and the DirectX 12
        // entry needs a runtime the APK may not carry, so its status line belongs right here.
        rendererSpinner = settingSpinner(graphics, R.string.launcher_renderer,
            R.array.renderer_labels);
        rendererDescription = statusText();
        graphics.addView(rendererDescription);
        vkd3dStatus = statusText();
        graphics.addView(vkd3dStatus);
        rendererSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRendererDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateRendererDescription();
            }
        });
        driverStatus = statusText();
        graphics.addView(driverStatus);
        driverSpinner = settingSpinner(graphics, R.string.launcher_driver,
            R.array.driver_labels);
        renderSpinner = settingSpinner(graphics, R.string.launcher_render_mode,
            R.array.render_mode_labels);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyDriverPresetToLauncher();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                applyDriverPresetToLauncher();
            }
        });
        LinearLayout driverButtons = row();
        driverButtons.addView(button(R.string.launcher_import_driver, view -> chooseDriver()), weighted());
        driverButtons.addView(button(R.string.launcher_driver_folder, view -> openFiles("transfer")), weighted());
        graphics.addView(driverButtons);
        graphics.addView(button(R.string.launcher_vkd3d_folder, view -> openVkd3dFolder()));

        // Presentation queue depth. The native side asks the Vulkan driver for three swap chain
        // images instead of two, which is what stops a single late compositor frame from stalling
        // the render thread; players who would rather have that frame of latency back need a way
        // to say so, and the in-game Video menu does not carry this key at all.
        tripleBufferSpinner = settingSpinner(graphics, R.string.launcher_triple_buffering,
            R.array.triple_buffer_labels);
        tripleBufferDescription = statusText();
        graphics.addView(tripleBufferDescription);
        tripleBufferSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTripleBufferDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateTripleBufferDescription();
            }
        });

        // The runtime touch settings (on-screen controls, camera and stick) now live
        // in the in-game options menu; only the layout editor stays here as it launches
        // the game renderer.
        LinearLayout controls = card(R.string.launcher_controls);
        controls.addView(text(getString(R.string.launcher_controls_moved), 14, false));
        Button editLayout = button(R.string.launcher_edit_layout, view -> launchLayoutEditor());
        controls.addView(editLayout);
        page.addView(controls);

        LinearLayout debug = collapsibleCard(page, R.string.launcher_debug, "expand_debug", false);
        skipIntro = checkBox(R.string.launcher_skip_intro);
        validation = checkBox(R.string.launcher_validation);
        gfxCapture = checkBox(R.string.launcher_gfx_capture);
        forceBc = checkBox(R.string.launcher_force_bc);
        debug.addView(skipIntro);
        debug.addView(validation);
        debug.addView(gfxCapture);
        debug.addView(forceBc);
        diagnosticsStatus = statusText();
        debug.addView(diagnosticsStatus);
        debug.addView(button(R.string.launcher_open_logs, view -> openFiles("transfer")));

        playButton = button(R.string.launcher_play, view -> launchGame(false));
        playButton.setTextSize(18);
        LinearLayout.LayoutParams playParams = matchWrap();
        playParams.topMargin = dp(8);
        page.addView(playButton, playParams);
        return scroll;
    }

    private void refreshStatuses() {
        lastInstallState = inspectInstallation();
        boolean stagedInstall = hasStagedGamePackages();
        installStatus.setText(stagedInstall && !lastInstallState.ready
            ? getString(R.string.launcher_install_staged)
            : lastInstallState.message);
        installStatus.setTextColor(lastInstallState.ready ? Color.rgb(25, 120, 55)
            : stagedInstall ? Color.rgb(180, 110, 20) : Color.rgb(180, 45, 35));
        playButton.setEnabled(lastInstallState.ready || stagedInstall);

        File installedMarker = new File(getFilesDir(), "turnip/last_imported_driver.txt");
        File recoveryMarker = new File(getFilesDir(), "turnip/vulkan_startup_state.txt");
        int pending = 0;
        for (File importDir : AppStorage.driverImportDirs(this)) {
            pending += countDriverPackages(importDir);
        }
        String imported = readFirstLine(installedMarker);
        if (recoveryMarker.isFile()) {
            driverStatus.setText(R.string.launcher_driver_recovery);
            driverStatus.setTextColor(Color.rgb(180, 45, 35));
        } else if (pending > 0) {
            driverStatus.setText(getString(R.string.launcher_driver_pending, pending));
            driverStatus.setTextColor(Color.DKGRAY);
        } else if (!imported.isEmpty()) {
            driverStatus.setText(getString(R.string.launcher_driver_installed, imported));
            driverStatus.setTextColor(Color.DKGRAY);
        } else {
            driverStatus.setText(R.string.launcher_driver_builtin);
            driverStatus.setTextColor(Color.DKGRAY);
        }

        File log = new File(AppStorage.transferRoot(this), "log.txt");
        diagnosticsStatus.setText(log.isFile()
            ? getString(R.string.launcher_log_found, formatBytes(log.length()))
            : getString(R.string.launcher_log_missing));
        refreshVkd3dStatus();
    }

    private InstallState inspectInstallation() {
        File root = AppStorage.activeGameRoot(this);
        if (!root.exists() && !root.mkdirs()) {
            return new InstallState(false, getString(R.string.error_storage_create, root));
        }
        File probe = new File(root, ".launcher_write_probe");
        try {
            if (!probe.createNewFile() && !probe.isFile()) {
                return new InstallState(false, getString(R.string.error_storage_write, root));
            }
            probe.delete();
        } catch (IOException exception) {
            return new InstallState(false, getString(R.string.error_storage_write, root));
        }

        // Sonic the Hedgehog (2006) has no title update: the dump is just
        // game/ (+ optional dlc/), so only game/default.xex is required here.
        // Each DLC directory is validated by the download.arc marker the native
        // installer writes (see install/installer.cpp DLCValidationFile).
        LinkedHashMap<String, File> required = new LinkedHashMap<>();
        required.put("game/default.xex", new File(root, "game/default.xex"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, File> item : required.entrySet()) {
            if (!item.getValue().isFile() || item.getValue().length() == 0) {
                missing.add(item.getKey());
            }
        }
        if (!missing.isEmpty()) {
            File misplaced = findFile(root, "default.xex", 3);
            if (misplaced != null && !misplaced.equals(required.get("game/default.xex"))) {
                return new InstallState(false, getString(R.string.error_game_nested,
                    relativePath(root, misplaced), root));
            }
            File[] rootFiles = root.listFiles();
            if (rootFiles == null || rootFiles.length == 0) {
                return new InstallState(false, getString(R.string.error_game_missing, root));
            }
            return new InstallState(false, getString(R.string.error_game_partial,
                joinLines(missing), root));
        }

        int dlc = 0;
        for (String directory : DLC_DIRECTORIES) {
            if (new File(root, "dlc/" + directory + "/download.arc").isFile()) dlc++;
        }
        return new InstallState(true, dlc == DLC_DIRECTORIES.length
            ? getString(R.string.game_ready_all_dlc)
            : getString(R.string.game_ready_missing_dlc, dlc, DLC_DIRECTORIES.length));
    }

    private void loadSettings() {
        Map<String, String> config = readConfig(AppStorage.configFile(this));
        String renderer = config.get("Video.GraphicsAPI");
        // Desktop configs (and older Android ones) may hold "Auto"; on Android that has always
        // meant Vulkan, so the launcher shows the resolved choice rather than a third state.
        if (renderer != null && renderer.replace("\"", "").trim().equalsIgnoreCase("Auto")) {
            renderer = "Vulkan";
        }
        select(rendererSpinner, renderer, RENDERER_VALUES);
        updateRendererDescription();
        select(driverSpinner, config.get("Video.VulkanDriver"), DRIVER_VALUES);
        select(renderSpinner, config.get("Video.RenderMode"), RENDER_MODE_VALUES);
        select(tripleBufferSpinner, config.get("Video.TripleBuffering"), TRIPLE_BUFFER_VALUES);
        updateTripleBufferDescription();
        applyDriverPresetToLauncher();
        skipIntro.setChecked(Boolean.parseBoolean(config.get("Codes.SkipIntroLogos")));
        validation.setChecked(new File(getFilesDir(), "turnip/vk_layer_settings.txt").isFile());
        gfxCapture.setChecked(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt").isFile());
        forceBc.setChecked(new File(getFilesDir(), "force_bc.txt").isFile());
    }

    private boolean saveSettings() {
        applyDriverPresetToLauncher();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("Video.GraphicsAPI", quote(rendererValue()));
        values.put("Video.VulkanDriver", quote(DRIVER_VALUES[driverSpinner.getSelectedItemPosition()]));
        values.put("Video.RenderMode", quote(RENDER_MODE_VALUES[renderSpinner.getSelectedItemPosition()]));
        values.put("Video.TripleBuffering", quote(TRIPLE_BUFFER_VALUES[tripleBufferSelectedIndex()]));
        values.put("Codes.SkipIntroLogos", Boolean.toString(skipIntro.isChecked()));
        try {
            patchConfig(AppStorage.configFile(this), values);
            setMarker(new File(getFilesDir(), "turnip/vk_layer_settings.txt"), validation.isChecked(),
                "khronos_validation.enables = VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT\n");
            setMarker(new File(AppStorage.driverImportDir(this), "gfxrecon_capture.txt"), gfxCapture.isChecked(), "");
            setMarker(new File(getFilesDir(), "force_bc.txt"), forceBc.isChecked(), "");
            return true;
        } catch (IOException exception) {
            showError(getString(R.string.error_settings_save, exception.getMessage()));
            return false;
        }
    }

    private void launchGame(boolean editControls) {
        launchGame(editControls, false);
    }

    /** {@code rendererConfirmed} skips the DirectX 12 availability prompt (answered already). */
    private void launchGame(boolean editControls, boolean rendererConfirmed) {
        if (!rendererConfirmed && !confirmDirectX12(editControls)) return;
        if (!saveSettings()) return;
        InstallState current = inspectInstallation();
        if (!current.ready && !hasStagedGamePackages()) {
            showError(current.message);
            refreshStatuses();
            return;
        }
        if (editControls) {
            try {
                setMarker(new File(AppStorage.activeGameRoot(this), "touch_layout_edit.txt"), true, "1\n");
            } catch (IOException exception) {
                showError(getString(R.string.error_layout_editor, exception.getMessage()));
                return;
            }
        }
        Intent game = new Intent(this, SDLActivity.class);
        game.putExtra("org.libsdl.app.EDIT_TOUCH_LAYOUT", editControls);
        startActivity(game);
    }

    private void launchLayoutEditor() {
        InstallState current = inspectInstallation();
        if (!current.ready) {
            showError(getString(R.string.error_layout_requires_game) + "\n\n" + current.message);
            return;
        }
        launchGame(true);
    }

    private void chooseDriver() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/octet-stream", "application/zip", "application/x-zip-compressed"
        });
        startActivityForResult(intent, REQUEST_DRIVER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_GAME_PACKAGES) {
            stageGamePackages(data);
            return;
        }
        if (data.getData() == null) return;
        switch (requestCode) {
            case REQUEST_GAME_ZIP:
                startInstall(data.getData(), true);
                return;
            case REQUEST_GAME_TREE:
                startInstall(data.getData(), false);
                return;
            case REQUEST_DRIVER:
                break;
            default:
                return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".so") && !lower.endsWith(".zip")) {
            showError(getString(R.string.error_driver_extension));
            return;
        }
        File directory = AppStorage.driverImportDir(this);
        directory.mkdirs();
        File target = uniqueFile(directory, safeName(name));
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Cannot open selected document");
            byte[] buffer = new byte[64 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new IOException("Selected file is empty");
            driverSpinner.setSelection(DRIVER_IMPORTED);
            Toast.makeText(this, getString(R.string.driver_imported, target.getName()), Toast.LENGTH_LONG).show();
            refreshStatuses();
        } catch (IOException exception) {
            target.delete();
            showError(getString(R.string.error_driver_copy, exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Game files / mod installer: copy from a user-picked ZIP archive or
    // folder into the game root, locating the content root automatically.
    // ------------------------------------------------------------------

    private void chooseInstallSource() {
        String[] items = new String[] { getString(R.string.install_source_zip), getString(R.string.install_source_folder),
            getString(R.string.install_source_iso_packages) };
        new AlertDialog.Builder(this)
            .setTitle(R.string.launcher_install_game)
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/zip", "application/x-zip-compressed", "application/octet-stream" });
                    startActivityForResult(intent, REQUEST_GAME_ZIP);
                } else if (which == 1) {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_GAME_TREE);
                } else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, REQUEST_GAME_PACKAGES);
                }
            })
            .show();
    }

    private void maybeCheckForUpdates() {
        long lastCheck = prefs.getLong("update_last_check", 0);
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            checkForUpdates(false);
        }
    }

    private void checkForUpdates(boolean manual) {
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_checking);
        UpdateManager.check(this, (update, error) -> {
            updateButton.setEnabled(true);
            if (error != null) {
                updateStatus.setText(getString(R.string.update_error, error));
                return;
            }
            prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply();
            if (update == null) {
                updateStatus.setText(getString(R.string.update_up_to_date, UpdateManager.currentVersion(this)));
                if (manual) Toast.makeText(this, R.string.update_up_to_date_short, Toast.LENGTH_LONG).show();
                return;
            }
            updateStatus.setText(getString(R.string.update_available, update.version));
            String notes = update.notes != null ? update.notes.trim() : "";
            if (notes.length() > 3000) notes = notes.substring(0, 3000) + "…";
            String message = getString(R.string.update_dialog_message, update.version,
                formatBytes(update.size), notes);
            new AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download, (dialog, which) -> downloadUpdate(update))
                .show();
        });
    }

    private void downloadUpdate(UpdateManager.UpdateInfo update) {
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_downloading);
        UpdateManager.download(this, update, new UpdateManager.DownloadCallback() {
            @Override
            public void progress(long downloaded, long total) {
                updateStatus.setText(getString(R.string.update_download_progress,
                    formatBytes(downloaded), formatBytes(total)));
            }

            @Override
            public void complete(String error) {
                updateButton.setEnabled(true);
                updateStatus.setText(error == null
                    ? getString(R.string.update_ready_to_install)
                    : getString(R.string.update_error, error));
            }
        });
    }

    private boolean hasStagedGamePackages() {
        File staging = new File(AppStorage.activeGameRoot(this), "to_install");
        File[] files = staging.listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        return files != null && files.length > 0;
    }

    private void stageGamePackages(Intent data) {
        List<Uri> sources = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) sources.add(uri);
            }
        } else if (data.getData() != null) {
            sources.add(data.getData());
        }
        if (sources.isEmpty()) return;

        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.install_scanning), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.install_progress_title)
            .setView(progress.label)
            .setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true)
            .show();

        new Thread(() -> {
            try {
                File staging = new File(AppStorage.activeGameRoot(this), "to_install");
                if (!staging.isDirectory() && !staging.mkdirs()) {
                    throw new IOException("Cannot create " + staging);
                }
                for (Uri source : sources) {
                    if (progress.cancelled) break;
                    String name = safeName(queryDisplayName(source));
                    if (name.isEmpty()) name = "source.bin";
                    File destination = uniqueFile(staging, name);
                    File temporary = new File(destination.getPath() + ".part");
                    try {
                        try (InputStream input = openSourceStream(source)) {
                            copyStreamToFile(input, temporary, progress);
                        }
                        if (progress.cancelled) {
                            Files.deleteIfExists(temporary.toPath());
                            break;
                        }
                        Files.move(temporary.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temporary.toPath());
                    }
                }
                finishInstall(progress, progress.cancelled
                    ? getString(R.string.install_cancelled)
                    : getString(R.string.install_done_iso_staged), !progress.cancelled);
            } catch (Exception exception) {
                String reason = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
                finishInstall(progress, getString(R.string.error_install_failed, reason), false);
            }
        }, "iso-stager").start();
    }

    /** One copyable file inside the picked source, addressed by a relative path. */
    private static final class SourceEntry {
        final String path;     // normalized, '/'-separated, no leading slash
        final Uri document;    // tree sources only; null for zip entries
        SourceEntry(String path, Uri document) {
            this.path = path;
            this.document = document;
        }
    }

    private static final class InstallProgress {
        volatile boolean cancelled;
        TextView label;
        AlertDialog dialog;
        int files;
        long bytes;
    }

    private void startInstall(Uri source, boolean isZip) {
        InstallProgress progress = new InstallProgress();
        progress.label = text(getString(R.string.install_scanning), 15, false);
        progress.label.setPadding(dp(20), dp(16), dp(20), dp(16));
        progress.dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.install_progress_title)
            .setView(progress.label)
            .setCancelable(false)
            .setNegativeButton(R.string.install_cancel, (dialog, which) -> progress.cancelled = true)
            .show();

        new Thread(() -> {
            try {
                List<SourceEntry> entries = isZip ? listZipEntries(source) : listTreeEntries(source);
                Map<SourceEntry, File> plan = planGameInstall(entries);

                if (plan.isEmpty()) {
                    finishInstall(progress, getString(R.string.error_install_no_game), false);
                    return;
                }

                if (isZip) {
                    copyZipEntries(source, plan, progress);
                } else {
                    copyTreeEntries(plan, progress);
                }

                if (progress.cancelled) {
                    finishInstall(progress, getString(R.string.install_cancelled), false);
                } else {
                    finishInstall(progress, getString(R.string.install_done_game), true);
                }
            } catch (Exception exception) {
                String reason = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
                finishInstall(progress, getString(R.string.error_install_failed, reason), false);
            }
        }, "installer").start();
    }

    private void finishInstall(InstallProgress progress, String message, boolean success) {
        runOnUiThread(() -> {
            progress.dialog.dismiss();
            new AlertDialog.Builder(this)
                .setTitle(success ? R.string.install_progress_title : R.string.error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            refreshStatuses();
        });
    }

    private void publishProgress(InstallProgress progress) {
        String status = getString(R.string.install_progress_status, progress.files, formatBytes(progress.bytes));
        runOnUiThread(() -> progress.label.setText(status));
    }

    /** Normalizes a zip/tree relative path; returns null when it must be skipped. */
    private static String normalizeRelativePath(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.endsWith("/")) return null;
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return path;
    }

    private List<SourceEntry> listZipEntries(Uri source) throws IOException {
        List<SourceEntry> entries = new ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                openSourceStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = normalizeRelativePath(entry.getName());
                if (path != null) entries.add(new SourceEntry(path, null));
            }
        }
        return entries;
    }

    private List<SourceEntry> listTreeEntries(Uri treeUri) {
        List<SourceEntry> entries = new ArrayList<>();
        listTreeChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", entries, 0);
        return entries;
    }

    private void listTreeChildren(Uri treeUri, String documentId, String prefix,
            List<SourceEntry> out, int depth) {
        if (depth > 8) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (android.database.Cursor cursor = getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (name == null || childId == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    listTreeChildren(treeUri, childId, prefix + name + "/", out, depth + 1);
                } else {
                    String path = normalizeRelativePath(prefix + name);
                    if (path != null) {
                        out.add(new SourceEntry(path,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)));
                    }
                }
            }
        }
    }

    /** Everything under the folder that holds game/default.xex goes into the game root. */
    private Map<SourceEntry, File> planGameInstall(List<SourceEntry> entries) {
        String marker = "game/default.xex";
        String prefix = null;
        for (SourceEntry entry : entries) {
            if (entry.path.equals(marker) || entry.path.endsWith("/" + marker)) {
                String candidate = entry.path.substring(0, entry.path.length() - marker.length());
                if (prefix == null || candidate.length() < prefix.length()) prefix = candidate;
            }
        }
        Map<SourceEntry, File> plan = new LinkedHashMap<>();
        if (prefix == null) return plan;
        File root = AppStorage.activeGameRoot(this);
        for (SourceEntry entry : entries) {
            if (entry.path.startsWith(prefix)) {
                plan.put(entry, new File(root, entry.path.substring(prefix.length())));
            }
        }
        return plan;
    }

    private InputStream openSourceStream(Uri uri) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Cannot open the selected source");
        return input;
    }

    private void copyZipEntries(Uri source, Map<SourceEntry, File> plan, InstallProgress progress)
            throws IOException {
        Map<String, File> byPath = new LinkedHashMap<>();
        for (Map.Entry<SourceEntry, File> item : plan.entrySet()) {
            byPath.put(item.getKey().path, item.getValue());
        }
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                openSourceStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && !progress.cancelled) {
                if (entry.isDirectory()) continue;
                String path = normalizeRelativePath(entry.getName());
                File destination = path != null ? byPath.get(path) : null;
                if (destination == null) continue;
                copyStreamToFile(zip, destination, progress);
            }
        }
    }

    private void copyTreeEntries(Map<SourceEntry, File> plan, InstallProgress progress)
            throws IOException {
        for (Map.Entry<SourceEntry, File> item : plan.entrySet()) {
            if (progress.cancelled) return;
            try (InputStream input = openSourceStream(item.getKey().document)) {
                copyStreamToFile(input, item.getValue(), progress);
            }
        }
    }

    private void copyStreamToFile(InputStream input, File destination, InstallProgress progress)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            long sinceUpdate = 0;
            while ((count = input.read(buffer)) >= 0) {
                if (progress.cancelled) return;
                output.write(buffer, 0, count);
                progress.bytes += count;
                sinceUpdate += count;
                if (sinceUpdate >= 16 * 1024 * 1024) {
                    sinceUpdate = 0;
                    publishProgress(progress);
                }
            }
        }
        progress.files++;
        if (progress.files % 25 == 0) publishProgress(progress);
    }

    private void openFiles(String rootId) {
        try {
            Uri directory = DocumentsContract.buildRootUri(getPackageName() + ".documents", rootId);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(directory, "vnd.android.document/root");
            intent.setClipData(ClipData.newRawUri("Marathon Recompiled files", directory));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(intent);
        } catch (Exception exception) {
            showError(getString(R.string.error_open_files));
        }
    }

    private LinearLayout card(int titleId) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        TextView title = text(getString(titleId), 19, true);
        title.setPadding(0, 0, 0, dp(7));
        card.addView(title);
        return card;
    }

    /**
     * A card whose body collapses/expands when its header is tapped. The expanded
     * state is remembered per card in the activity preferences. Adds itself to
     * {@code page} and returns the body layout for the caller to populate.
     */
    private LinearLayout collapsibleCard(LinearLayout page, int titleId, String stateKey, boolean defaultExpanded) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);

        final LinearLayout body = column();
        final boolean expanded = prefs.getBoolean(stateKey, defaultExpanded);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);

        final String label = getString(titleId);
        final TextView title = text(label, 19, true);
        title.setPadding(0, 0, 0, dp(7));
        title.setText((expanded ? "▾  " : "▸  ") + label);
        title.setOnClickListener(view -> {
            boolean nowExpanded = body.getVisibility() != View.VISIBLE;
            body.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            title.setText((nowExpanded ? "▾  " : "▸  ") + label);
            prefs.edit().putBoolean(stateKey, nowExpanded).apply();
        });

        card.addView(title);
        card.addView(body);
        page.addView(card);
        return body;
    }

    private Spinner settingSpinner(LinearLayout parent, int labelId, int arrayId) {
        TextView label = text(getString(labelId), 14, true);
        parent.addView(label);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayId,
            android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, matchWrap());
        return spinner;
    }

    private void applyDriverPresetToLauncher() {
        // Experimental bundled drivers are intentionally not exposed by the launcher.
        // Keep render-mode selection independent for Auto/System/Imported.
        if (driverSpinner == null || renderSpinner == null) return;
        renderSpinner.setEnabled(true);
    }

    /**
     * One-line explanation of the selected renderer under the spinner. A DirectX 12 choice on
     * Android is a translation layer on top of the same Vulkan driver, not a second driver, so
     * the text says what it changes and - equally important - what it does not.
     */
    private void updateRendererDescription() {
        if (rendererSpinner == null || rendererDescription == null) return;
        boolean directX12 = rendererSelectedIndex() == RENDERER_DIRECTX12;
        rendererDescription.setText(directX12
            ? R.string.launcher_renderer_dx12_info
            : R.string.launcher_renderer_vulkan_info);
        rendererDescription.setTextColor(directX12 && !hasVkd3dRuntime()
            ? Color.rgb(180, 45, 35) : Color.DKGRAY);
        refreshVkd3dStatus();
    }

    /**
     * One-line explanation of the swap chain depth. Deliberately says what the third image buys
     * (the render thread no longer waits on the compositor) and what it costs (up to one frame of
     * input lag), because "triple buffering" on its own reads like a pure performance option.
     */
    private void updateTripleBufferDescription() {
        if (tripleBufferSpinner == null || tripleBufferDescription == null) return;
        int index = tripleBufferSelectedIndex();
        tripleBufferDescription.setText(index == TRIPLE_BUFFER_OFF
            ? R.string.launcher_triple_buffering_off_info
            : (index == TRIPLE_BUFFER_ON
                ? R.string.launcher_triple_buffering_on_info
                : R.string.launcher_triple_buffering_auto_info));
    }

    private int tripleBufferSelectedIndex() {
        if (tripleBufferSpinner == null) return TRIPLE_BUFFER_AUTO;
        int index = tripleBufferSpinner.getSelectedItemPosition();
        return index >= 0 && index < TRIPLE_BUFFER_VALUES.length ? index : TRIPLE_BUFFER_AUTO;
    }

    private int rendererSelectedIndex() {
        if (rendererSpinner == null) return RENDERER_VULKAN;
        int index = rendererSpinner.getSelectedItemPosition();
        return index >= 0 && index < RENDERER_VALUES.length ? index : RENDERER_VULKAN;
    }

    private String rendererValue() {
        return RENDERER_VALUES[rendererSelectedIndex()];
    }

    private boolean directX12Selected() {
        return rendererSpinner != null && rendererSelectedIndex() == RENDERER_DIRECTX12;
    }

    /** Any vkd3d library the game could load: shipped in the APK or dropped into an import folder. */
    private File findVkd3dRuntime() {
        List<File> directories = new ArrayList<>();
        if (getApplicationInfo() != null && getApplicationInfo().nativeLibraryDir != null) {
            directories.add(new File(getApplicationInfo().nativeLibraryDir));
        }
        directories.addAll(Arrays.asList(AppStorage.vkd3dImportDirs(this)));

        for (File directory : directories) {
            if (!directory.isDirectory()) continue;
            for (String name : VKD3D_LIBRARY_NAMES) {
                File candidate = new File(directory, name);
                if (candidate.isFile() && candidate.length() > 0) return candidate;
            }
            // Community builds carry version suffixes, so accept the same prefixes the native
            // probe uses (see LooksLikeVkd3dLibrary in os/android/vkd3d_android.cpp).
            File[] loose = directory.listFiles(file -> {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                return file.isFile() && file.length() > 0 && lower.endsWith(".so")
                    && (lower.startsWith("libvkd3d") || lower.startsWith("vkd3d")
                        || lower.startsWith("libd3d12") || lower.startsWith("d3d12"));
            });
            if (loose != null && loose.length > 0) {
                Arrays.sort(loose);
                return loose[0];
            }
        }
        return null;
    }

    private boolean hasVkd3dRuntime() {
        return findVkd3dRuntime() != null;
    }

    /** Token before '|' in the status line the native probe writes at game start; "" if unknown. */
    private static String vkd3dStatusToken(String status) {
        int bar = status.indexOf('|');
        return bar >= 0 ? status.substring(0, bar) : status;
    }

    /** Shows the last probed verdict so the option is not a guess based on file names alone. */
    private void refreshVkd3dStatus() {
        if (vkd3dStatus == null) return;
        String status = readFirstLine(AppStorage.vkd3dStatusFile(this));
        String token = vkd3dStatusToken(status);
        String detail = status.length() > token.length() ? status.substring(token.length() + 1) : "";

        if (token.equals("no-renderer-in-build")) {
            vkd3dStatus.setText(R.string.launcher_vkd3d_no_backend);
            vkd3dStatus.setTextColor(Color.rgb(180, 45, 35));
        } else if (token.equals("ready")) {
            vkd3dStatus.setText(getString(R.string.launcher_vkd3d_ready, detail));
            vkd3dStatus.setTextColor(Color.rgb(25, 120, 55));
        } else if (token.equals("bad-runtime")) {
            vkd3dStatus.setText(getString(R.string.launcher_vkd3d_bad, detail));
            vkd3dStatus.setTextColor(Color.rgb(180, 45, 35));
        } else if (hasVkd3dRuntime()) {
            vkd3dStatus.setText(R.string.launcher_vkd3d_unprobed);
            vkd3dStatus.setTextColor(Color.DKGRAY);
        } else {
            vkd3dStatus.setText(R.string.launcher_vkd3d_missing);
            vkd3dStatus.setTextColor(Color.DKGRAY);
        }
    }

    /**
     * DirectX 12 only runs when a vkd3d runtime is installed. The game would quietly fall back to
     * Vulkan, which is the right behaviour but the wrong surprise, so ask before the first frame.
     */
    private boolean confirmDirectX12(final boolean editControls) {
        if (!directX12Selected()) return true;

        File runtime = findVkd3dRuntime();
        String token = vkd3dStatusToken(readFirstLine(AppStorage.vkd3dStatusFile(this)));
        boolean blocked = runtime == null || token.equals("no-renderer-in-build") || token.equals("bad-runtime");
        if (!blocked) return true;

        new AlertDialog.Builder(this)
            .setTitle(R.string.launcher_renderer_dx12_title)
            .setMessage(runtime == null ? R.string.launcher_renderer_dx12_no_runtime
                : R.string.launcher_renderer_dx12_broken)
            .setPositiveButton(R.string.launcher_renderer_switch_vulkan, (dialog, which) -> {
                rendererSpinner.setSelection(RENDERER_VULKAN);
                launchGame(editControls, true);
            })
            .setNegativeButton(R.string.launcher_renderer_launch_anyway, (dialog, which) -> launchGame(editControls, true))
            .setNeutralButton(android.R.string.cancel, null)
            .show();
        return false;
    }

    /** Creates the vkd3d drop folder (with a readme) and shows it in the system Files app. */
    private void openVkd3dFolder() {
        File directory = AppStorage.vkd3dImportDir(this);
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create " + directory);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(new File(directory, "readme.txt")), StandardCharsets.UTF_8)) {
                writer.write(getString(R.string.vkd3d_folder_readme));
            }
        } catch (IOException exception) {
            showError(getString(R.string.error_vkd3d_folder, exception.getMessage()));
            return;
        }
        openFiles("transfer");
    }

    private TextView statusText() {
        TextView status = text("", 14, false);
        status.setPadding(0, 0, 0, dp(7));
        return status;
    }

    private CheckBox checkBox(int stringId) {
        CheckBox box = new CheckBox(this);
        box.setText(stringId);
        return box;
    }

    private Button button(int stringId, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(stringId);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(25, 25, 25));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private static String quote(String value) { return "\"" + value + "\""; }

    private static void select(Spinner spinner, String value, String[] options) {
        if (value == null) return;
        value = value.replace("\"", "").trim();
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static Map<String, String> readConfig(File file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!file.isFile()) return result;
        String section = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                } else if (!trimmed.startsWith("#") && !trimmed.isEmpty()) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) result.put(section + "." + trimmed.substring(0, equals).trim(),
                        trimmed.substring(equals + 1).trim());
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static void patchConfig(File file, LinkedHashMap<String, String> changes) throws IOException {
        List<String> lines = file.isFile()
            ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
            : new ArrayList<>();
        Map<String, Boolean> written = new LinkedHashMap<>();
        for (String key : changes.keySet()) written.put(key, false);
        String section = "";
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length() - 1).trim();
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || trimmed.startsWith("#")) continue;
            String full = section + "." + trimmed.substring(0, equals).trim();
            if (changes.containsKey(full)) {
                lines.set(i, trimmed.substring(0, equals).trim() + " = " + changes.get(full));
                written.put(full, true);
            }
        }
        for (String full : changes.keySet()) {
            if (written.get(full)) continue;
            int dot = full.indexOf('.');
            String wantedSection = full.substring(0, dot);
            String name = full.substring(dot + 1);
            int insertAt = lines.size();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.equals("[" + wantedSection + "]")) {
                    found = true;
                    insertAt = i + 1;
                    while (insertAt < lines.size() && !lines.get(insertAt).trim().startsWith("[")) insertAt++;
                    break;
                }
            }
            if (!found) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
                lines.add("[" + wantedSection + "]");
                insertAt = lines.size();
            }
            lines.add(insertAt, name + " = " + changes.get(full));
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File temporary = new File(parent, file.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            for (String line : lines) writer.write(line + "\n");
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setMarker(File file, boolean enabled, String contents) throws IOException {
        if (!enabled) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(contents);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
            new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return "vulkan_driver.so";
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static File uniqueFile(File directory, String name) {
        File result = new File(directory, name);
        if (!result.exists()) return result;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            result = new File(directory, base + "_" + i + extension);
            if (!result.exists()) return result;
        }
    }

    private static int countDriverPackages(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() &&
            (file.getName().toLowerCase(Locale.ROOT).endsWith(".so") ||
             file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")));
        return files == null ? 0 : files.length;
    }

    private static String readFirstLine(File file) {
        if (!file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) { return ""; }
    }

    private static File findFile(File root, String name, int depth) {
        if (depth < 0) return null;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && child.getName().equalsIgnoreCase(name)) return child;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFile(child, name, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String relativePath(File root, File file) {
        try { return root.toPath().relativize(file.toPath()).toString(); }
        catch (Exception ignored) { return file.toString(); }
    }

    private static String joinLines(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append("\n• ").append(value);
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
