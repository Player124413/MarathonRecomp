package com.marathonrecomp.launcher;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.marathonrecomp.launcher.input.Binding;
import com.marathonrecomp.launcher.input.ControlDescription;
import com.marathonrecomp.launcher.input.ControlElement;
import com.marathonrecomp.launcher.input.ControlLayout;
import com.marathonrecomp.launcher.input.TouchOverlayView;

/**
 * Layout editor for the on-screen controls.
 *
 * <p>Drag any control to move it, then use the panel at the bottom to resize it, change
 * its opacity, switch what it is bound to, or delete it. New buttons and sticks can be
 * added, and the whole thing can be reset back to the stock Xbox 360 arrangement.</p>
 *
 * <p>The editor deliberately reuses the very same {@link TouchOverlayView} the game uses,
 * just in edit mode, so what you arrange here is exactly what you get in game — including
 * sizes, because positions are stored as screen fractions.</p>
 */
public class ControlsEditorActivity extends AppCompatActivity
        implements TouchOverlayView.EditorListener {

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;

    private TouchOverlayView overlay;
    private View panel;
    private TextView selectionLabel;
    private SeekBar sizeBar;
    private SeekBar alphaBar;
    private CheckBox toggleMode;
    private CheckBox roundShape;
    private Button bindingButton;
    private Button deleteButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        setContentView(R.layout.activity_controls_editor);

        FrameLayout root = findViewById(R.id.editor_root);
        overlay = new TouchOverlayView(this);
        overlay.setEditMode(true);
        overlay.setEditorListener(this);
        root.addView(overlay, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        panel = findViewById(R.id.editor_panel);
        selectionLabel = findViewById(R.id.editor_selection_label);
        sizeBar = findViewById(R.id.editor_size);
        alphaBar = findViewById(R.id.editor_alpha);
        toggleMode = findViewById(R.id.editor_toggle_mode);
        roundShape = findViewById(R.id.editor_round);
        bindingButton = findViewById(R.id.editor_binding);
        deleteButton = findViewById(R.id.editor_delete);

        setupPanel();

        findViewById(R.id.editor_add).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.editor_reset).setOnClickListener(v -> confirmReset());
        findViewById(R.id.editor_save).setOnClickListener(v -> {
            overlay.save();
            Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        onSelectionChanged(null);
    }

    private void setupPanel() {
        sizeBar.setMax(100);
        alphaBar.setMax(255);

        sizeBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                ControlElement selected = overlay.selected();

                if (selected != null && fromUser) {
                    selected.desc.scale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * (progress / 100f);
                    overlay.invalidate();
                    updateSelectionLabel(selected);
                }
            }
        });

        alphaBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                ControlElement selected = overlay.selected();

                if (selected != null && fromUser) {
                    selected.desc.alpha = Math.max(30, progress);
                    overlay.invalidate();
                }
            }
        });

        toggleMode.setOnCheckedChangeListener((v, checked) -> {
            ControlElement selected = overlay.selected();

            if (selected != null) {
                selected.desc.toggle = checked;
            }
        });

        roundShape.setOnCheckedChangeListener((v, checked) -> {
            ControlElement selected = overlay.selected();

            if (selected != null) {
                selected.desc.circle = checked;
                overlay.invalidate();
            }
        });

        bindingButton.setOnClickListener(v -> showBindingDialog());

        deleteButton.setOnClickListener(v -> {
            overlay.removeSelected();
            onSelectionChanged(null);
        });
    }

    // ------------------------------------------------------------- selection ----

    @Override
    public void onSelectionChanged(@Nullable ControlElement element) {
        boolean has = element != null;

        sizeBar.setEnabled(has);
        alphaBar.setEnabled(has);
        toggleMode.setEnabled(has);
        roundShape.setEnabled(has);
        bindingButton.setEnabled(has);
        deleteButton.setEnabled(has);

        if (!has) {
            selectionLabel.setText(R.string.editor_nothing_selected);
            return;
        }

        ControlDescription d = element.desc;

        sizeBar.setProgress(Math.round((d.scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * 100f));
        alphaBar.setProgress(d.alpha);
        toggleMode.setChecked(d.toggle);
        roundShape.setChecked(d.circle);
        bindingButton.setText(getString(R.string.editor_binding_format, d.binding.label()));

        // Shape and latching only mean something for buttons.
        boolean isButton = d.type == ControlDescription.Type.BUTTON;
        toggleMode.setVisibility(isButton ? View.VISIBLE : View.GONE);
        roundShape.setVisibility(isButton ? View.VISIBLE : View.GONE);
        bindingButton.setVisibility(isButton ? View.VISIBLE : View.GONE);

        updateSelectionLabel(element);
    }

    private void updateSelectionLabel(ControlElement element) {
        selectionLabel.setText(getString(R.string.editor_selection_format,
                element.desc.label(), Math.round(element.desc.scale * 100)));
    }

    // ------------------------------------------------------------------ menus ----

    private void showAddDialog() {
        CharSequence[] options = {
                getString(R.string.editor_add_button),
                getString(R.string.editor_add_stick_left),
                getString(R.string.editor_add_stick_right),
                getString(R.string.editor_add_dpad)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_add_title)
                .setItems(options, (dialog, which) -> {
                    ControlDescription d;

                    switch (which) {
                        case 1:
                            d = new ControlDescription(ControlDescription.Type.STICK,
                                    Binding.LEFT_STICK, 0.5f, 0.5f);
                            break;
                        case 2:
                            d = new ControlDescription(ControlDescription.Type.STICK,
                                    Binding.RIGHT_STICK, 0.5f, 0.5f);
                            break;
                        case 3:
                            d = new ControlDescription(ControlDescription.Type.DPAD,
                                    Binding.DPAD_UP, 0.5f, 0.5f);
                            break;
                        case 0:
                        default:
                            d = new ControlDescription(ControlDescription.Type.BUTTON,
                                    Binding.A, 0.5f, 0.5f);
                            break;
                    }

                    overlay.addElement(d);
                    onSelectionChanged(overlay.selected());
                })
                .show();
    }

    private void showBindingDialog() {
        ControlElement selected = overlay.selected();

        if (selected == null) {
            return;
        }

        Binding[] bindings = Binding.assignable();
        CharSequence[] labels = new CharSequence[bindings.length];

        for (int i = 0; i < bindings.length; i++) {
            labels[i] = bindings[i].label() + "  (" + bindings[i].name() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_binding_title)
                .setItems(labels, (dialog, which) -> {
                    selected.desc.binding = bindings[which];
                    selected.desc.text = null;
                    bindingButton.setText(getString(R.string.editor_binding_format,
                            bindings[which].label()));
                    overlay.invalidate();
                    updateSelectionLabel(selected);
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_reset_title)
                .setMessage(R.string.editor_reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.editor_reset_confirm, (dialog, which) -> {
                    ControlLayout.reset(this);
                    overlay.setLayout(ControlLayout.defaultLayout());
                    onSelectionChanged(null);
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        // Leaving without saving would silently throw the arrangement away.
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_exit_title)
                .setMessage(R.string.editor_exit_message)
                .setNeutralButton(android.R.string.cancel, null)
                .setNegativeButton(R.string.editor_exit_discard, (d, w) -> super.onBackPressed())
                .setPositiveButton(R.string.editor_exit_save, (d, w) -> {
                    overlay.save();
                    super.onBackPressed();
                })
                .show();
    }

    private void goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /** Saves writing three empty methods every time a seek bar is needed. */
    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
