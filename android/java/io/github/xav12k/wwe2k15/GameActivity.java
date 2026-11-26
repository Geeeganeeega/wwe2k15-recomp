// WWE 2K15 recompiled, Android: the game's activity, in its own process (android:process=":game").
// SDL's activity (org.libsdl.app, zlib licence, vendored in the SDK) owns the window, input and sound; this class names
// the native libraries, the app's files folder and the run's arguments, and asks before Back leaves the game.
// Structure follows an earlier port's GameActivity.
package io.github.xav12k.wwe2k15;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.libsdl.app.SDLActivity;

public class GameActivity extends SDLActivity {
    private static final String TAG = "wwe2k15";
    static final String EXTRA_ARGS = "wwe2k15.args";
    // The launcher's touch choices: int[4] = {mode (0 auto, 1 always, 2 never), size %, opacity %, vibrate 0/1}.
    static final String EXTRA_TOUCH = "wwe2k15.touch";
    // The launcher's FPS overlay choice: a bit mask of FpsOverlay.SHOW_* (0 = off).
    static final String EXTRA_FPS = "wwe2k15.fps";

    // libmain.so (src/frame_counter.h): the game's frames so far.
    static native long nativeFrameCount();

    private TouchOverlay overlay;
    private FpsOverlay fps;

    private AlertDialog quitDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The app's own folder (Android/data/<package>/files): the SDK's SDL_main makes it the working folder and HOME
        // (logs\ and user data land there). The variable's name is the SDK's (windowed_app_main_sdl.cpp). Set before
        // SDL starts the native thread.
        File files = filesDir();
        try {
            Os.setenv("WWE2K14_FILES_DIR", files.getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "setenv WWE2K14_FILES_DIR failed", e);
        }
        super.onCreate(savedInstanceState);
        try {
            getWindow().setSustainedPerformanceMode(true);  // steady clocks (no boost-then-throttle) where the phone supports it
        } catch (Throwable ignored) {
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The picture reaches the screen's edges past the camera cut-out.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // The screen's fastest mode at its current size (e.g. 120 Hz): the frame pacing aims at whole divisions of it.
        try {
            android.view.Display disp = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = disp.getMode(), best = cur;
            for (android.view.Display.Mode m : disp.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate()) best = m;
            }
            lp.preferredDisplayModeId = best.getModeId();
        } catch (Throwable ignored) {
        }
        getWindow().setAttributes(lp);

        // Touch controls (the launcher's choices).
        int[] touch = getIntent().getIntArrayExtra(EXTRA_TOUCH);
        int mode = touch != null && touch.length == 4 ? touch[0] : TouchOverlay.MODE_OFF;
        if (mode != TouchOverlay.MODE_OFF && mLayout != null) {
            overlay = new TouchOverlay(this, mode, touch[1], touch[2], touch[3] != 0);
            mLayout.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        int what = getIntent().getIntExtra(EXTRA_FPS, 0);
        if (what != 0 && mLayout != null) {
            fps = new FpsOverlay(this, what);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            flp.leftMargin = (int) (64 * getResources().getDisplayMetrics().density);  // clear of the camera cut-out
            flp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
            mLayout.addView(fps, flp);
            fps.start();
        }
        // Touch and controller input arrives unbuffered (Android 12+), without waiting for the next screen refresh.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                getWindow().getDecorView().requestUnbufferedDispatch(android.view.InputDevice.SOURCE_ANY);
            } catch (Throwable ignored) {
            }
        }
        // The launcher's "DLC trial": the game running for 40 s means the DLC did not stop it.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> new File(getFilesDir(), "dlc_trial").delete(), 40000);
        // A small button in the top-right corner: back to the launcher without closing the whole game.
        if (mLayout != null) {
            final float d = getResources().getDisplayMetrics().density;
            android.widget.TextView home = new android.widget.TextView(this);
            home.setText("MENU");
            home.setTextColor(0xFFFFFFFF);
            home.setTextSize(12);
            home.setGravity(Gravity.CENTER);
            home.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x80000000);
            bg.setCornerRadius(10 * d);
            bg.setStroke((int) d, 0x60FFFFFF);
            home.setBackground(bg);
            home.setAlpha(0.6f);
            home.setOnClickListener(v -> askQuit());
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    (int) (30 * d), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            hp.bottomMargin = (int) (6 * d);
            mLayout.addView(home, hp);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (overlay != null && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) overlay.onScreenTouch();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (overlay != null && (e.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN) overlay.onPadInput();
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (overlay != null && (e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                && e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            for (int axis : new int[] {android.view.MotionEvent.AXIS_X, android.view.MotionEvent.AXIS_Y,
                    android.view.MotionEvent.AXIS_Z, android.view.MotionEvent.AXIS_RZ, android.view.MotionEvent.AXIS_HAT_X,
                    android.view.MotionEvent.AXIS_HAT_Y, android.view.MotionEvent.AXIS_LTRIGGER, android.view.MotionEvent.AXIS_RTRIGGER}) {
                if (Math.abs(e.getAxisValue(axis)) > 0.35f) {
                    overlay.onPadInput();
                    break;
                }
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    protected void onDestroy() {
        if (overlay != null) overlay.release();
        if (fps != null) fps.stop();
        super.onDestroy();
    }

    // The game is landscape only. SDL asks for an orientation from the game's window when it is made; this keeps the
    // manifest's sensorLandscape.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        askQuit();
    }

    private void askQuit() {
        if (overlay != null && overlay.editing()) {  // Back while moving the controls = CANCEL
            overlay.cancelEdit();
            return;
        }
        if (quitDialog != null && quitDialog.isShowing()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Quit to the start screen?")
                .setMessage("Anything not saved in the game is lost.")
                .setPositiveButton("Quit", (d, w) -> quitToStart())
                .setNegativeButton("Keep playing", null);
        if (overlay != null && overlay.controlsShown())
            b.setNeutralButton("Edit touch controls", (d, w) -> overlay.startEdit(null));
        quitDialog = b.create();
        quitDialog.show();
    }

    // The start screen in the same task, then this process ends at once: no game shutdown to wait for.
    private void quitToStart() {
        Log.i(TAG, "quit to the start screen");
        new File(getFilesDir(), "dlc_trial").delete();
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(0, 0);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private File filesDir() {
        File files = getExternalFilesDir(null);
        return files != null ? files : getFilesDir();
    }

    // SDL lives inside librexruntime.so (no libSDL3.so); libmain.so (last) holds SDL_main.
    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "rexruntime", "main"};
    }

    // The run's arguments come from the start screen (EXTRA_ARGS), one command-line argument per entry.
    @Override
    protected String[] getArguments() {
        String[] given = getIntent().getStringArrayExtra(EXTRA_ARGS);
        List<String> list = new ArrayList<>();
        if (given != null) {
            for (String a : given) list.add(a);
        }
        Log.i(TAG, "arguments: " + list);
        return list.toArray(new String[0]);
    }
}
