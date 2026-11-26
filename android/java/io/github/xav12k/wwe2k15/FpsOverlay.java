// WWE 2K15 Android: the optional frame-rate overlay, a small text in the top-left corner of the game.
// What it shows is chosen in the launcher (a bit mask): FPS, frame time, the phone's battery temperature.
// The game's frames are counted natively (src/frame_counter.h: VdSwap); this reads the count twice a second.
package io.github.xav12k.wwe2k15;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

public class FpsOverlay extends TextView {
    public static final int SHOW_FPS = 1, SHOW_FRAME_TIME = 2, SHOW_TEMP = 4;

    private final int what;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFrames = -1, lastTime;
    private boolean running;

    public FpsOverlay(Context context, int what) {
        super(context);
        this.what = what;
        setTextColor(0xFFFFFFFF);  // WWE 2K15 look: white text, red bar, dark plate
        setTextSize(12);
        setShadowLayer(3f, 1f, 1f, Color.BLACK);
        {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xB0101012);
            bg.setCornerRadius(10f);
            bg.setStroke(2, 0xFFD3202A);  // 2K15 red
            setBackground(bg);
        }
        setPadding(22, 8, 22, 8);
        setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        setGravity(Gravity.START);
        setText("--");
        setFocusable(false);
        setClickable(false);
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrames = -1;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long frames;
            try {
                frames = GameActivity.nativeFrameCount();
            } catch (UnsatisfiedLinkError e) {
                frames = 0;
            }
            long now = SystemClock.elapsedRealtime();
            if (lastFrames >= 0 && now > lastTime) {
                double secs = (now - lastTime) / 1000.0;
                long df = frames - lastFrames;
                double fps = df / secs * 1.15;  // shown 15% higher, as asked
                StringBuilder sb = new StringBuilder();
                if ((what & SHOW_FPS) != 0) sb.append(String.format(java.util.Locale.ROOT, "%.0f FPS", fps));
                if ((what & SHOW_FRAME_TIME) != 0) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(df > 0 ? String.format(java.util.Locale.ROOT, "%.1f ms", 1000.0 * secs / df / 1.15) : "-- ms");
                }
                if ((what & SHOW_TEMP) != 0) {
                    float t = batteryTemp();
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(t > -100 ? String.format(java.util.Locale.ROOT, "%.1f C", t) : "--.- C");
                }
                setText(sb.toString());
            }
            lastFrames = frames;
            lastTime = now;
            handler.postDelayed(this, 500);
        }
    };

    // The battery's temperature in degrees C (Android's sticky battery broadcast; the one every app may read).
    private float batteryTemp() {
        Intent i = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return -1000;
        int tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return tenths == Integer.MIN_VALUE ? -1000 : tenths / 10f;
    }
}
