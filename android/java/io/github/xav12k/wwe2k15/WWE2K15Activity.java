// WWE 2K15 Android: the game's activity. SDL's activity (org.libsdl.app, zlib licence, from the SDK) owns the window,
// input and sound; this class names the native libraries, the app's files folder and the run's arguments, and adds
// the touch controls. Layout from the WWE '13 Android port (dzepdzep/wwe13-recomp-android, BSD 3-Clause).
package io.github.xav12k.wwe2k15;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.libsdl.app.SDLActivity;

public class WWE2K15Activity extends SDLActivity {
    private static final String TAG = "wwe2k15";
    private TouchControls touch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The app's own folder (Android/data/<package>/files): the SDK's SDL_main makes it the working folder and HOME
        // (logs, saves and the game's user data land there). The variable's name is the SDK's
        // (windowed_app_main_sdl.cpp, from the WWE 2K14 port). Set before SDL starts the native thread.
        File files = filesRoot();
        try {
            Os.setenv("WWE2K14_FILES_DIR", files.getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "setenv WWE2K14_FILES_DIR failed", e);
        }
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            getWindow().setSustainedPerformanceMode(true);  // steady clocks where the phone supports it
        } catch (Throwable ignored) {
        }
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
        // Nothing of the activity window may cover the game's SurfaceView.
        getWindow().setBackgroundDrawable(null);
        if (mSurface != null) mSurface.setZOrderOnTop(true);
        // On-screen touch controls (launcher setting; hidden while a controller is connected by default).
        touch = TouchControls.install(this);
        showLoading();
    }

    // ---- "loading" note: the game needs some seconds (shaders, files) before its first picture. Without a note the
    // screen stays black and players leave the app; Android then closes the game in the background.

    private static native long nativeFrameCount();

    private android.widget.TextView loading;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long loadingStart;

    private void showLoading() {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText("LOADING WWE 2K15\u2026\nThe first start takes a little longer. Please wait.");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
        t.setShadowLayer(12, 0, 0, 0xFFE3141C);
        t.setBackgroundColor(0xFF140002);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        final android.view.View decor = getWindow().getDecorView();
        decor.post(() -> {
            try {
                lp.token = decor.getWindowToken();
                getWindowManager().addView(t, lp);
                loading = t;
                loadingStart = android.os.SystemClock.uptimeMillis();
                handler.postDelayed(this::checkLoading, 250);
            } catch (RuntimeException e) {
                Log.w(TAG, "loading note: " + e);
            }
        });
    }

    private void checkLoading() {
        if (loading == null) return;
        long frames = 0;
        try {
            frames = nativeFrameCount();
        } catch (Throwable ignored) {  // the game's library not loaded yet
        }
        long waited = android.os.SystemClock.uptimeMillis() - loadingStart;
        if (frames >= 20 || waited > 180000) {
            hideLoading();
            return;
        }
        int dots = (int) (waited / 500 % 4);
        loading.setText("LOADING WWE 2K15" + "...".substring(0, dots) + "\nThe first start takes a little longer. Please wait.");
        handler.postDelayed(this::checkLoading, 250);
    }

    private void hideLoading() {
        if (loading == null) return;
        try {
            getWindowManager().removeView(loading);
        } catch (RuntimeException ignored) {
        }
        loading = null;
    }

    // ---- back: an edge swipe or the back key closes the game only when pressed twice within 2 seconds.
    private long lastBack;

    @Override
    public void onBackPressed() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
            return;
        }
        lastBack = now;
        android.widget.Toast.makeText(this, "Press back again to quit the game", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideLoading();
        if (touch != null) touch.stop();
        touch = null;
        super.onDestroy();
    }

    // The game is landscape only. SDL asks for an orientation from the game's window; keep the manifest's.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private File filesRoot() {
        File f = getExternalFilesDir(null);
        return f != null ? f : getFilesDir();
    }

    // SDL lives inside librexruntime.so (no libSDL3.so); libmain.so (last) holds SDL_main.
    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "rexruntime", "main"};
    }

    // The run's arguments: launch_options.txt, written by the launcher (LauncherActivity.startGame), one per line.
    @Override
    protected String[] getArguments() {
        List<String> list = new ArrayList<>();
        File f = new File(filesRoot(), "launch_options.txt");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "no launch_options.txt: " + e.getMessage());
        }
        Log.i(TAG, "arguments: " + list);
        return list.toArray(new String[0]);
    }
}
