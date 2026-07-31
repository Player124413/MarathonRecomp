package com.marathonrecomp.launcher;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.marathonrecomp.launcher.emu.Emulator;
import com.marathonrecomp.launcher.emu.EmulatorInstaller;
import com.marathonrecomp.launcher.gpu.GpuInfo;
import com.marathonrecomp.launcher.gpu.VulkanDriver;

import java.io.File;
import java.io.InputStream;

/**
 * Home screen: pick the emulation backend, point the launcher at the game, tweak input,
 * and start playing.
 */
public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "MarathonDroid";

    /** Some file managers report zips with odd MIME types, so accept anything. */
    private static final String[] ARCHIVE_MIME_TYPES = {
            "application/zip", "application/octet-stream", "*/*"
    };

    private LauncherPrefs prefs;

    private Spinner emulatorSpinner;
    private TextView runtimeStatus;
    private TextView gameStatus;
    private CheckBox compatibility;
    private CheckBox fexTso;
    private CheckBox touchControls;
    private CheckBox haptics;
    private CheckBox useTurnip;
    private TextView gpuStatus;
    private Button playButton;
    private Button removeGameButton;

    private ActivityResultLauncher<Uri> pickGameDir;
    private ActivityResultLauncher<String[]> pickRuntimeZip;
    private ActivityResultLauncher<String[]> pickGameZip;
    private ActivityResultLauncher<String[]> pickDriver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs = new LauncherPrefs(this);
        NativeBridge.load();

        emulatorSpinner = findViewById(R.id.emulator_spinner);
        runtimeStatus = findViewById(R.id.runtime_status);
        gameStatus = findViewById(R.id.game_status);
        compatibility = findViewById(R.id.compatibility_mode);
        fexTso = findViewById(R.id.fex_tso);
        touchControls = findViewById(R.id.touch_controls);
        haptics = findViewById(R.id.haptics);
        useTurnip = findViewById(R.id.use_turnip);
        gpuStatus = findViewById(R.id.gpu_status);
        playButton = findViewById(R.id.play_button);
        removeGameButton = findViewById(R.id.remove_game_button);

        setupEmulatorSpinner();
        setupPickers();
        setupCheckBoxes();

        findViewById(R.id.pick_game_button).setOnClickListener(v -> {
            try {
                pickGameDir.launch(null);
            } catch (Exception e) {
                toast(getString(R.string.error_no_file_picker));
            }
        });

        findViewById(R.id.install_runtime_button).setOnClickListener(v ->
                pickRuntimeZip.launch(ARCHIVE_MIME_TYPES));

        findViewById(R.id.install_game_button).setOnClickListener(v ->
                pickGameZip.launch(ARCHIVE_MIME_TYPES));

        findViewById(R.id.install_driver_button).setOnClickListener(v ->
                pickDriver.launch(ARCHIVE_MIME_TYPES));

        removeGameButton.setOnClickListener(v -> confirmRemoveGame());

        findViewById(R.id.edit_controls_button).setOnClickListener(v ->
                startActivity(new Intent(this, ControlsEditorActivity.class)));

        playButton.setOnClickListener(v -> startGame());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // -------------------------------------------------------------- ui setup ----

    private void setupEmulatorSpinner() {
        String[] names = new String[Emulator.values().length];

        for (int i = 0; i < names.length; i++) {
            names[i] = Emulator.values()[i].displayName;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        emulatorSpinner.setAdapter(adapter);
        emulatorSpinner.setSelection(prefs.emulator().ordinal());

        emulatorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Emulator selected = Emulator.values()[position];

                if (selected != prefs.emulator()) {
                    prefs.setEmulator(selected);
                    Log.i(TAG, "Backend switched to " + selected.displayName);
                }

                refreshStatus();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void setupCheckBoxes() {
        compatibility.setChecked(prefs.isCompatibilityMode());
        compatibility.setOnCheckedChangeListener((v, checked) -> prefs.setCompatibilityMode(checked));

        fexTso.setChecked(prefs.isFexTsoEnabled());
        fexTso.setOnCheckedChangeListener((v, checked) -> prefs.setFexTsoEnabled(checked));

        touchControls.setChecked(prefs.isTouchControlsEnabled());
        touchControls.setOnCheckedChangeListener((v, checked) -> prefs.setTouchControlsEnabled(checked));

        haptics.setChecked(prefs.isHapticsEnabled());
        haptics.setOnCheckedChangeListener((v, checked) -> prefs.setHapticsEnabled(checked));

        useTurnip.setChecked(prefs.isTurnipEnabled());
        useTurnip.setOnCheckedChangeListener((v, checked) -> prefs.setTurnipEnabled(checked));
    }

    private void setupPickers() {
        pickGameDir = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri == null) {
                        return;
                    }

                    String path = resolveTreePath(uri);

                    if (path == null) {
                        toast(getString(R.string.error_unsupported_location));
                        return;
                    }

                    prefs.setGameDir(path);
                    refreshStatus();
                });

        pickRuntimeZip = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        installRuntime(uri);
                    }
                });

        pickGameZip = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        installGame(uri);
                    }
                });

        pickDriver = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        installDriver(uri);
                    }
                });
    }

    /**
     * Unpacks a zipped Linux build into the app's own storage.
     *
     * <p>Allowed to live there because box64 reads the game with fopen() and maps it
     * itself rather than exec()ing it, so Android's no-exec data directory is not in the
     * way — see {@link GameInstaller}.</p>
     */
    private void installGame(Uri uri) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.game_installing)
                .setMessage("")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            GameInstaller.Result result;

            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    result = null;
                } else {
                    result = GameInstaller.install(this, in, (name, bytes) ->
                            runOnUiThread(() -> progress.setMessage(
                                    getString(R.string.game_installing_file, name))));
                }
            } catch (Exception e) {
                Log.e(TAG, "Game install failed", e);
                result = GameInstaller.Result.failure(String.valueOf(e.getMessage()));
            }

            final GameInstaller.Result r = result;

            runOnUiThread(() -> {
                progress.dismiss();

                if (r != null && r.success) {
                    // The unpacked copy takes priority, so clear any stale folder choice.
                    prefs.setGameDir(null);
                    toast(getString(R.string.game_installed, r.fileCount));
                } else {
                    toast(getString(R.string.game_install_failed,
                            r != null ? r.error : "cannot read the archive"));
                }

                refreshStatus();
            });
        }, "game-install").start();
    }

    /** Imports a Turnip / custom Vulkan driver (.so or an AdrenoTools .zip). */
    private void installDriver(Uri uri) {
        toast(getString(R.string.driver_installing));

        new Thread(() -> {
            String error;
            String name = queryDisplayName(uri);

            try (InputStream in = getContentResolver().openInputStream(uri)) {
                error = in == null
                        ? "cannot read the file"
                        : VulkanDriver.install(this, name, in);
            } catch (Exception e) {
                Log.e(TAG, "Driver import failed", e);
                error = String.valueOf(e.getMessage());
            }

            final String failure = error;

            runOnUiThread(() -> {
                if (failure == null) {
                    prefs.setTurnipEnabled(true);
                    useTurnip.setChecked(true);
                } else {
                    toast(getString(R.string.driver_install_failed, failure));
                }

                refreshStatus();
            });
        }, "driver-install").start();
    }

    private void confirmRemoveGame() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.game_remove_title)
                .setMessage(R.string.game_remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_remove_game, (d, w) -> {
                    GameInstaller.uninstall(this);
                    refreshStatus();
                })
                .show();
    }

    /** Best-effort display name for a picked document, used to spot ".zip" vs ".so". */
    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);

                if (index >= 0) {
                    return c.getString(index);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read the name of " + uri, e);
        }

        return uri.getLastPathSegment();
    }

    // ---------------------------------------------------------------- actions ----

    private void installRuntime(Uri uri) {
        Emulator target = prefs.emulator();
        toast(getString(R.string.runtime_installing, target.displayName));

        new Thread(() -> {
            boolean ok = false;
            String error = null;

            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ok = EmulatorInstaller.installFromZip(this, target, in);
                }
            } catch (Exception e) {
                Log.e(TAG, "Runtime install failed", e);
                error = e.getMessage();
            }

            final boolean success = ok;
            final String message = error;

            runOnUiThread(() -> {
                if (success) {
                    toast(getString(R.string.runtime_installed, target.displayName));
                } else {
                    toast(getString(R.string.runtime_install_failed,
                            message != null ? message : target.binaryName));
                }

                refreshStatus();
            });
        }, "runtime-install").start();
    }

    private void startGame() {
        Intent intent = new Intent(this, GameActivity.class);
        startActivity(intent);
    }

    // ----------------------------------------------------------------- status ----

    private void refreshStatus() {
        Emulator emulator = prefs.emulator();
        boolean runtimeReady = EmulatorInstaller.isInstalled(this, emulator);

        if (emulator.bundled) {
            runtimeStatus.setText(runtimeReady
                    ? getString(R.string.runtime_bundled, emulator.displayName)
                    : getString(R.string.runtime_bundled_missing, emulator.displayName));
        } else if (!runtimeReady) {
            runtimeStatus.setText(getString(R.string.runtime_not_installed, emulator.displayName));
        } else if (EmulatorInstaller.requiresExternalExec(this, emulator)) {
            // Imported binaries live in the app's data directory, which Android 10+ mounts
            // no-exec. Say so now rather than failing with "permission denied" at launch.
            runtimeStatus.setText(getString(R.string.runtime_imported_noexec, emulator.displayName));
        } else {
            runtimeStatus.setText(getString(R.string.runtime_ready, emulator.displayName));
        }

        // A bundled backend has nothing to import.
        findViewById(R.id.install_runtime_button)
                .setVisibility(emulator.bundled ? View.GONE : View.VISIBLE);

        // An unpacked install wins over a picked folder, matching GameLauncher.
        boolean installed = GameInstaller.isInstalled(this);
        String gameDir = prefs.gameDir();
        boolean pickedReady = !installed && gameDir != null
                && new File(gameDir, GameLauncher.GAME_BINARY).isFile();
        boolean gameReady = installed || pickedReady;

        if (installed) {
            gameStatus.setText(getString(R.string.game_installed_internal,
                    GameInstaller.gameDir(this).getAbsolutePath()));
        } else if (pickedReady) {
            gameStatus.setText(getString(R.string.game_ready, gameDir));
        } else {
            gameStatus.setText(getString(R.string.game_not_selected));
        }

        removeGameButton.setVisibility(installed ? View.VISIBLE : View.GONE);

        refreshGraphicsStatus();

        // FEX's TSO switch is meaningless under box64.
        fexTso.setVisibility(emulator == Emulator.FEX ? View.VISIBLE : View.GONE);

        playButton.setEnabled(runtimeReady && gameReady);
    }

    /**
     * Turns a SAF tree Uri into a real filesystem path.
     *
     * <p>The emulated game is a plain Linux process: it cannot read through the Storage
     * Access Framework, so the game files have to live somewhere reachable with open().
     * Primary shared storage maps predictably; anything else (SD cards, USB) does not,
     * and the user is told to move the files instead of silently failing later.</p>
     */
    @Nullable
    private String resolveTreePath(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            String[] parts = documentId.split(":", 2);

            if (parts.length == 2 && "primary".equalsIgnoreCase(parts[0])) {
                File external = android.os.Environment.getExternalStorageDirectory();
                return new File(external, parts[1]).getAbsolutePath();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot resolve " + treeUri, e);
        }

        return null;
    }

    /** GPU line plus the state of the imported Vulkan driver. */
    private void refreshGraphicsStatus() {
        String driver = VulkanDriver.installedName(this);
        boolean hasDriver = driver != null;

        useTurnip.setEnabled(hasDriver);

        // Probe the GPU once, off the UI thread, then fold it into the status line.
        new Thread(() -> {
            GpuInfo gpu = GpuInfo.query();

            runOnUiThread(() -> {
                String gpuLine;

                if (!gpu.isKnown()) {
                    gpuLine = getString(R.string.gpu_detected, gpu.displayName());
                } else if (gpu.isTurnipIncompatible()) {
                    // Turnip is Adreno-only; say so rather than letting it fail later.
                    gpuLine = getString(R.string.gpu_not_adreno, gpu.displayName());
                } else {
                    gpuLine = getString(R.string.gpu_detected, gpu.displayName());
                }

                String driverLine = hasDriver
                        ? getString(R.string.driver_installed, driver)
                        : getString(R.string.driver_none);

                gpuStatus.setText(gpuLine + "\n" + driverLine);
            });
        }, "gpu-probe").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Small helper used by the "what is this" info buttons. */
    void showInfo(int titleRes, int messageRes) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
