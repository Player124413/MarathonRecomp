package com.marathonrecomp.launcher;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.marathonrecomp.launcher.input.Binding;
import com.marathonrecomp.launcher.input.GamepadHandler;
import com.marathonrecomp.launcher.input.TouchOverlayView;

/**
 * The in-game screen.
 *
 * <p>It owns the touch overlay and the physical gamepad handler, keeps the game process
 * alive, and pumps rumble requests back to the phone's vibrator. The game itself renders
 * into its own X surface; this activity is the input and control layer on top.</p>
 */
public class GameActivity extends AppCompatActivity
        implements TouchOverlayView.ActionListener, GamepadHandler.Listener {

    private static final String TAG = "MarathonDroid/Game";

    private LauncherPrefs prefs;
    private GameLauncher launcher;
    private TouchOverlayView overlay;
    private TextView statusText;
    private GamepadHandler gamepadHandler;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastRumbleCounter = -1;

    /** Polls the shared memory pad for rumble the game asked for. */
    private final Runnable rumblePump = new Runnable() {
        @Override
        public void run() {
            pollRumble();
            handler.postDelayed(this, 60);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new LauncherPrefs(this);
        launcher = new GameLauncher(this);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        setContentView(R.layout.activity_game);

        FrameLayout root = findViewById(R.id.game_root);
        statusText = findViewById(R.id.game_status_text);

        overlay = new TouchOverlayView(this);
        overlay.setActionListener(this);
        overlay.setHapticEnabled(prefs.isHapticsEnabled());
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (!prefs.isTouchControlsEnabled()) {
            overlay.setVisibility(View.GONE);
        }

        gamepadHandler = new GamepadHandler(
                (InputManager) getSystemService(INPUT_SERVICE), overlay.pad(), this);

        startGame();
    }

    private void startGame() {
        String error = launcher.start(new GameLauncher.Listener() {
            @Override
            public void onStarted() {
                statusText.setText(R.string.game_starting);
                handler.postDelayed(() -> statusText.setVisibility(View.GONE), 4000);
            }

            @Override
            public void onExited(int exitCode) {
                Log.i(TAG, "Game process exited: " + exitCode);
                statusText.setVisibility(View.VISIBLE);
                statusText.setText(getString(R.string.game_exited, exitCode));
                handler.postDelayed(GameActivity.this::finish, 1500);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(GameActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        if (error != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.error_launch_title)
                    .setMessage(error)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .show();
        }
    }

    // ------------------------------------------------------------- lifecycle ----

    @Override
    protected void onStart() {
        super.onStart();
        gamepadHandler.start();
        handler.post(rumblePump);
    }

    @Override
    protected void onStop() {
        super.onStop();
        gamepadHandler.stop();
        handler.removeCallbacks(rumblePump);
    }

    @Override
    protected void onResume() {
        super.onResume();
        goFullscreen();
        overlay.setHapticEnabled(prefs.isHapticsEnabled());
        // The layout may have been edited while we were away.
        overlay.load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        launcher.stop();
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

    // ----------------------------------------------------------------- input ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A real controller's buttons must reach the game, not the activity's back stack.
        if (gamepadHandler != null && gamepadHandler.onKeyEvent(event)) {
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (gamepadHandler != null && gamepadHandler.onMotionEvent(event)) {
            return true;
        }

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onGamepadConnectionChanged(boolean connected) {
        // This is the rule the whole input layer hangs off: a physical pad makes the
        // on-screen controls disappear completely, and unplugging brings them back.
        overlay.setGamepadConnected(connected);

        if (connected) {
            Toast.makeText(this, R.string.gamepad_connected, Toast.LENGTH_SHORT).show();
        } else if (prefs.isTouchControlsEnabled()) {
            overlay.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.gamepad_disconnected, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLauncherAction(Binding binding) {
        switch (binding) {
            case EDIT_LAYOUT:
                startActivity(new Intent(this, ControlsEditorActivity.class));
                break;

            case MENU:
                showQuickMenu();
                break;

            default:
                break;
        }
    }

    private void showQuickMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_menu_title)
                .setItems(new CharSequence[] {
                        getString(R.string.quick_menu_resume),
                        getString(R.string.quick_menu_edit_controls),
                        getString(R.string.quick_menu_quit)
                }, (dialog, which) -> {
                    if (which == 1) {
                        startActivity(new Intent(this, ControlsEditorActivity.class));
                    } else if (which == 2) {
                        launcher.stop();
                        finish();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- rumble ----

    private void pollRumble() {
        if (!NativeBridge.isLoaded() || !NativeBridge.vpadIsOpen()) {
            return;
        }

        long packed = NativeBridge.vpadPollRumble(0);
        int counter = (int) ((packed >> 48) & 0xFFFFL);

        if (counter == lastRumbleCounter) {
            return;
        }

        lastRumbleCounter = counter;

        if (!prefs.isHapticsEnabled()) {
            return;
        }

        int left = (int) ((packed >> 16) & 0xFFFFL);
        int right = (int) (packed & 0xFFFFL);
        int strength = Math.max(left, right);

        if (strength <= 0) {
            return;
        }

        vibrate(Math.max(1, Math.min(255, strength * 255 / 65535)));
    }

    private void vibrate(int amplitude) {
        try {
            Vibrator vibrator;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            vibrator.vibrate(VibrationEffect.createOneShot(60, amplitude));
        } catch (Throwable t) {
            Log.w(TAG, "Rumble failed", t);
        }
    }
}
