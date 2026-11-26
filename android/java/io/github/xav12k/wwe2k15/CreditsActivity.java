// WWE 2K15 recompiled, Android: the credits card shown after Play, before the game starts.
// Credits fade in and out over 5 seconds, then the website over 2.5 seconds, then the game starts.
package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class CreditsActivity extends Activity {
    static final String EXTRA_NEXT = "next";
    private static final long FADE_MS = 500, CREDITS_MS = 5000, SITE_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private View credits, site;
    private boolean started, left;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        credits = buildCredits();
        site = buildSite();
        credits.setAlpha(0f);
        site.setAlpha(0f);
        root.addView(credits, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(site, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (!started) { started = true; runSequence(); }
    }

    // A tapped link opens the browser; the sequence picks up again where it was when the app comes back.
    @Override
    protected void onPause() {
        super.onPause();
        if (!left && started) { handler.removeCallbacksAndMessages(null); started = false; }
    }

    private void runSequence() {
        handler.removeCallbacksAndMessages(null);
        credits.setAlpha(0f);
        site.setAlpha(0f);
        credits.animate().alpha(1f).setDuration(FADE_MS).start();
        handler.postDelayed(() -> credits.animate().alpha(0f).setDuration(FADE_MS).start(), CREDITS_MS - FADE_MS);
        handler.postDelayed(() -> site.animate().alpha(1f).setDuration(FADE_MS).start(), CREDITS_MS);
        handler.postDelayed(() -> site.animate().alpha(0f).setDuration(FADE_MS).start(), CREDITS_MS + SITE_MS - FADE_MS);
        handler.postDelayed(this::launch, CREDITS_MS + SITE_MS);
    }

    private void launch() {
        if (left) return;
        left = true;
        Intent next = getIntent().getParcelableExtra(EXTRA_NEXT);
        if (next != null) startActivity(next);
        finish();
    }

    @Override
    public void onBackPressed() { launch(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildCredits() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F2347);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        TextView title = text("Credits", 20, Color.WHITE, true);
        title.setPadding(dp(4), 0, 0, dp(12));
        card.addView(title);
        card.addView(row("logo_xav", "Xav \"Shaddowxavier\"" , Logo.YOUTUBE, "YouTube", IntroActivity.YOUTUBE), rowLp());
        card.addView(row("logo_volyre", "Volyre Technologies", Logo.YOUTUBE, "YouTube", IntroActivity.YOUTUBE_FRIEND), rowLp());
        card.addView(row("logo_xav", "Xavier Gaming Emulation", Logo.TELEGRAM, "Telegram", IntroActivity.TELEGRAM), rowLp());
        card.addView(row("logo_xav", "Android Ports Community", Logo.TELEGRAM, "Telegram", IntroActivity.TELEGRAM_PORTS), rowLp());
        card.setMinimumWidth(dp(330));
        return card;
    }

    private View buildSite() {
        TextView t = text("volvisionmods.com", 22, Color.WHITE, true);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setOnClickListener(v -> open(IntroActivity.WEBSITE));
        return t;
    }

    private View row(String avatarName, String name, int logo, String label, String url) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A3560);
        bg.setCornerRadius(dp(16));
        r.setBackground(bg);
        r.setPadding(dp(10), dp(10), dp(14), dp(10));
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(getResources().getIdentifier(avatarName, "drawable", getPackageName()));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        r.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, 0, 0);
        t.addView(text(name, 17, Color.WHITE, true));
        LinearLayout link = new LinearLayout(this);
        link.setOrientation(LinearLayout.HORIZONTAL);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setPadding(0, dp(4), 0, 0);
        link.addView(new Logo(this, logo), new LinearLayout.LayoutParams(dp(26), dp(18)));
        TextView lt = text(label, 16, 0xFFB7C6E3, false);
        lt.setPadding(dp(8), 0, 0, 0);
        link.addView(lt);
        t.addView(link);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> open(url));
        return r;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        return lp;
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, url, Toast.LENGTH_LONG).show(); }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // The YouTube and Telegram marks, drawn in code.
    private static final class Logo extends View {
        static final int YOUTUBE = 0, TELEGRAM = 1;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Logo(Context c, int kind) { super(c); this.kind = kind; }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (kind == YOUTUBE) {
                p.setColor(0xFFFF0000);
                c.drawRoundRect(new RectF(0, 0, w, h), h * 0.3f, h * 0.3f, p);
                p.setColor(Color.WHITE);
                Path t = new Path();
                t.moveTo(w * 0.40f, h * 0.28f);
                t.lineTo(w * 0.40f, h * 0.72f);
                t.lineTo(w * 0.66f, h * 0.5f);
                t.close();
                c.drawPath(t, p);
            } else {
                float d = Math.min(w, h), cx = w / 2f, cy = h / 2f;
                p.setColor(0xFF2AABEE);
                c.drawCircle(cx, cy, d / 2f, p);
                p.setColor(Color.WHITE);
                Path t = new Path();
                t.moveTo(cx - d * 0.30f, cy - d * 0.02f);
                t.lineTo(cx + d * 0.30f, cy - d * 0.26f);
                t.lineTo(cx + d * 0.17f, cy + d * 0.28f);
                t.lineTo(cx - d * 0.02f, cy + d * 0.08f);
                t.lineTo(cx - d * 0.12f, cy + d * 0.22f);
                t.lineTo(cx - d * 0.10f, cy + d * 0.04f);
                t.close();
                c.drawPath(t, p);
            }
        }
    }
}
