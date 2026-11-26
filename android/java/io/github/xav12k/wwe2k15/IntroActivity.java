// WWE 2K15 recompiled, Android: the intro screen shown before the launcher. The video is assets/intro.mp4 in the APK
// (android/assets/intro.mp4, optional: without it the launcher opens straight away); it is copied to the cache folder
// here and played with a VideoView. Structure from the WWE 2K17 port. After 3 seconds a SKIP
// button appears and a tap anywhere skips; the credits links are always tappable.
package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;

public class IntroActivity extends Activity {
    static final String YOUTUBE = "https://youtube.com/@shaddowxavier?si=wKlnBO_oPWxGizBl";
    static final String YOUTUBE_FRIEND = "https://youtube.com/@volyre?si=K_bcGuR4CMRzEUVr";
    static final String TELEGRAM = "https://t.me/xaviergamingemulation";
    static final String TELEGRAM_PORTS = "https://t.me/AndroidPortsCommunity";
    static final String WEBSITE = "https://volvisionmods.com/";
    /** True once the intro has been shown in this process (MainActivity sends you back here until then). */
    static volatile boolean done;
    static final String EXTRA_CREDITS_ONLY = "credits_only";
    private boolean creditsOnly;

    private static final long MIN_MS = 3000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private boolean canSkip, finished;
    private Button skip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getSharedPreferences("wwe2k15", MODE_PRIVATE).edit().putBoolean("from_game", false).apply();  // a fresh start from the icon
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int vh = Math.min(dm.widthPixels, dm.heightPixels), vw = Math.round(vh * 16f / 9f);
        root.addView(video, new FrameLayout.LayoutParams(Math.min(vw, Math.max(dm.widthPixels, dm.heightPixels)), vh, Gravity.CENTER));
        root.setOnClickListener(v -> skipIfAllowed());
        video.setOnClickListener(v -> skipIfAllowed());

        skip = new Button(this);
        skip.setText("SKIP  \u25B6");
        skip.setAllCaps(false);
        skip.setTextColor(Color.WHITE);
        skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        skip.setBackground(rounded(0xB0161618, 0x80FFFFFF));
        skip.setVisibility(View.GONE);
        skip.setOnClickListener(v -> skipIfAllowed());
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.END);
        slp.topMargin = dp(16);
        slp.rightMargin = dp(20);
        root.addView(skip, slp);
        setContentView(root);

        hideBars();
        creditsOnly = getIntent().getBooleanExtra(EXTRA_CREDITS_ONLY, false);
        if (creditsOnly) { showCredits(); return; }
        start();
    }

    private void start() {
        boolean ok = false;
        try {
            File f = new File(getCacheDir(), "intro.mp4");
            if (copyAsset("intro.mp4", f) > 0) {
                video.setVideoURI(Uri.fromFile(f));
                video.setOnPreparedListener(mp -> mp.setLooping(false));
                video.setOnCompletionListener(mp -> next());
                video.setOnErrorListener((mp, w, e) -> { next(); return true; });
                video.start();
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        if (!ok) { next(); return; }
        handler.postDelayed(() -> { canSkip = true; skip.setVisibility(View.VISIBLE); }, MIN_MS);
    }

    // A tap anywhere (the video view swallows clicks, so the activity looks at the raw touch).
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (!credits && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) skipIfAllowed();
        return super.dispatchTouchEvent(e);
    }

    private void skipIfAllowed() {
        if (canSkip) next();
    }

    private void next() {
        if (finished) return;
        finished = true;
        done = true;
        try { video.stopPlayback(); } catch (Throwable ignored) { }
        leave();
    }

    private void leave() {
        if (!creditsOnly) startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // The credits: their own screen after the intro (and from the launcher's credits button).
    private void showCredits() {
        credits = true;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF080404, 0xFF1D0907, 0xFF3A0F08}));
        page.setPadding(dp(40), dp(16), dp(40), dp(16));
        TextView title = new TextView(this);
        title.setText("CREDITS");
        title.setTextColor(0xFFFF7A1A);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.2f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        page.addView(title);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(col);
        creditRow(col, "Xavier (Shaddow Xavier)", "Port and project owner", "YouTube", YOUTUBE);
        creditRow(col, "Volyre Technologies", "Helped with this project", "YouTube", YOUTUBE_FRIEND);
        creditRow(col, "Xavier Gaming Emulation", "Gaming community: gameplay and tutorials, game comparisons, tips, tricks and updates, emulation", "Telegram", TELEGRAM);
        creditRow(col, "Volvision Mods", "Our website", "Website", WEBSITE);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button go = new Button(this);
        go.setText(creditsOnly ? "CLOSE" : "CONTINUE");
        go.setTextColor(0xFF1A0A05);
        go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        go.setLetterSpacing(0.2f);
        go.setStateListAnimator(null);
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFFFFB03F, 0xFFF0792A, 0xFFE0261E});
        pg.setCornerRadius(dp(40));
        go.setBackground(pg);
        go.setOnClickListener(v -> leave());
        page.addView(go, new LinearLayout.LayoutParams(dp(260), dp(48)));
        setContentView(page);
        hideBars();
    }

    private void creditRow(LinearLayout col, String name, String what, String button, String url) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(rounded(0xFF2A1810, 0));
        r.setPadding(dp(16), dp(8), dp(12), dp(8));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        TextView w = new TextView(this);
        w.setText(what);
        w.setTextColor(0xFFC3C7CE);
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.addView(n);
        t.addView(w);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Button b = new Button(this);
        b.setText(button + " \u2197");
        b.setAllCaps(false);
        b.setTextColor(0xFFFF7A1A);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setBackground(rounded(0x33FF7A1A, 0x88FF7A1A));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { Toast.makeText(this, url, Toast.LENGTH_LONG).show(); }
        });
        r.addView(b, new LinearLayout.LayoutParams(-2, dp(38)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        col.addView(r, lp);
    }
    private boolean credits;

    @Override
    public void onBackPressed() {
        if (credits) leave(); else skipIfAllowed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!finished && !creditsOnly) video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (!finished && !creditsOnly && video != null && !video.isPlaying()) video.start();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void chip(LinearLayout row, String text, String url) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setBackground(rounded(0xB0161618, 0x60FFFFFF));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, url, Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
        lp.leftMargin = lp.rightMargin = dp(5);
        row.addView(b, lp);
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // Copies an APK asset to a file; returns its size, or 0 when the APK has no such asset.
    private long copyAsset(String name, File out) {
        try (java.io.InputStream in = getAssets().open(name);
             java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long n = 0;
            for (int r; (r = in.read(buf)) > 0; n += r) o.write(buf, 0, r);
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
