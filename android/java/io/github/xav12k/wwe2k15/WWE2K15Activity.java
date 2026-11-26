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
    }

    @Override
    protected void onDestroy() {
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
