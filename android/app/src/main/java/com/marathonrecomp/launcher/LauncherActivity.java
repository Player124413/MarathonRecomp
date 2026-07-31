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

import java.io.File;
import java.io.InputStream;

/**
 * Home screen: pick the emulation backend, point the launcher at the game, tweak input,
 * and start playing.
 */
public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "MarathonDroid";

    private LauncherPrefs prefs;

    private Spinner emulatorSpinner;
    private TextView runtimeStatus;
    private TextView gameStatus;
    private CheckBox compatibility;
    private CheckBox fexTso;
    private CheckBox touchControls;
    private CheckBox haptics;
    private Button playButton;

    private ActivityResultLauncher<Uri> pickGameDir;
    private ActivityResultLauncher<String[]> pickRuntimeZip;

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
        playButton = findViewById(R.id.play_button);

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
                pickRuntimeZip.launch(new String[] { "application/zip", "application/octet-stream", "*/*" }));

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

        runtimeStatus.setText(runtimeReady
                ? getString(R.string.runtime_ready, emulator.displayName)
                : getString(R.string.runtime_not_installed, emulator.displayName));

        String gameDir = prefs.gameDir();
        boolean gameReady = gameDir != null
                && new File(gameDir, GameLauncher.GAME_BINARY).isFile();

        gameStatus.setText(gameReady
                ? getString(R.string.game_ready, gameDir)
                : getString(R.string.game_not_selected));

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
