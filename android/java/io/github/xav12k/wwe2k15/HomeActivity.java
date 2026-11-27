package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The home screen: Shadow Xavier's WebView design (assets/launcher/index.html, used as it is). Every other screen
 * stays in LauncherActivity, which this one opens with LauncherActivity.EXTRA_OPEN_PAGE / EXTRA_START_GAME.
 *
 * Pictures: the page asks for images/bg_home.jpg and images/tile_*.jpg. They are not part of the app. They come from
 * the phone: the player's own WWE2K15_Launcher_WebView.zip in Download (copied in on start), GAME > Look in the
 * settings, or a long-press on a tile in the drawn settings home. Missing pictures leave the tile's dark red.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "wwe2k15";
    private static final String PAGE = "file:///android_asset/launcher/index.html";
    private static final String IMAGES = "/android_asset/launcher/images/";
    private static final String PICTURE_ZIP = "WWE2K15_Launcher_WebView.zip";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        // "Start the game directly" (GAME page setting): the settings screen handles that start, as before.
        if (savedInstanceState == null && prefs.getBoolean("skip_launcher", false) && prefs.getBoolean("game_ready", false)) {
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0002);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0D0002);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setMediaPlaybackRequiresUserGesture(true);
        // The page scales its 1088x540 design to the screen itself: no WebView zoom, no font-size zoom from the
        // phone's settings, one CSS pixel per dp.
        ws.setTextZoom(100);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        web.setInitialScale(0);
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i(TAG, "[home] " + m.message());
                return true;
            }
        });
        // Fit again whenever the view's size changes (rotation, cut-out, immersive bars hiding).
        web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) handler.postDelayed(this::refit, 50);
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String path = u.getPath();
                if ("file".equals(u.getScheme()) && path != null && path.startsWith(IMAGES)) return picture(path.substring(IMAGES.length()));
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;  // the home screen never navigates away
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pushInfo();
                addExtras();
                refit();
                handler.postDelayed(HomeActivity.this::refit, 400);
                handler.postDelayed(HomeActivity.this::refit, 1500);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        web.loadUrl(PAGE);
        web.requestFocus();
        immersive();
        importPicturesFromDownload();
        if (savedInstanceState == null) showSplash(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) {
            pushInfo();  // back from a settings page: the game may have been chosen there
            web.requestFocus();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** The design's own fit(), from the WebView's real size: the whole 1088x540 page on screen, centred. */
    private void refit() {
        if (web == null || web.getWidth() == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float w = web.getWidth() / d, h = web.getHeight() / d;
        web.evaluateJavascript("(function(){var s=document.getElementById('s');if(!s)return;"
                + "var W=" + w + ",H=" + h + ",k=Math.min(W/1088,H/540);"
                + "s.style.transform='translate(-50%,-50%) scale('+k+')';"
                + "if(!window._fitLogged){window._fitLogged=1;console.log('fit '+W+'x'+H+' inner '+innerWidth+'x'+innerHeight+' dpr '+devicePixelRatio+' scale '+k);}"
                + "})()", null);
    }

    /** Added at run time (index.html itself is not changed): tiles whose picture is missing show red art instead of a
     *  broken-picture icon, a DLC button next to HOME (Y on a controller), and one tap opens any tile. */
    private void addExtras() {
        if (web == null) return;
        web.evaluateJavascript("(function(){if(document.getElementById('xx-extra'))return;"
                + "var st=document.createElement('style');st.id='xx-extra';st.textContent="
                + "'.tile.xx-noimg{background:linear-gradient(135deg,#8a0012 0%,#3a0006 55%,#140002 100%)!important}"
                + ".tile.xx-noimg svg{width:44px!important;height:44px!important;right:22px!important;top:16px!important;opacity:.45!important}"
                + ".xx-dlc{position:absolute;left:250px;top:180px;height:30px;padding:0 18px 0 16px;border:0;"
                + "background:linear-gradient(90deg,#F0162F 0%,#B0081C 70%,#7A0412 100%);color:#fff;transform:skewX(-14deg);"
                + "box-shadow:inset -3px 0 0 #fff,0 6px 16px rgba(0,0,0,.55);font:italic 900 19px Roboto;letter-spacing:1.2px;cursor:pointer}"
                + ".xx-dlc span{display:inline-block;transform:skewX(14deg) scaleX(.86)}"
                + ".xx-dlc b{display:inline-block;width:16px;height:16px;border:1.6px solid #fff;border-radius:50%;font:700 10px Roboto;"
                + "line-height:13px;text-align:center;margin-left:10px;vertical-align:2px;font-style:normal}';"
                + "document.head.appendChild(st);"
                + "function miss(i){i.style.visibility='hidden';if(i.parentNode)i.parentNode.classList.add('xx-noimg');}"
                + "document.querySelectorAll('img').forEach(function(i){if(i.complete&&i.naturalWidth===0)miss(i);"
                + "i.addEventListener('error',function(){miss(i);});});"
                + "var b=document.createElement('button');b.className='xx-dlc';b.innerHTML='<span>DLC<b>Y</b></span>';"
                + "b.onclick=function(){if(window.Android&&Android.openSection)Android.openSection('dlc');};"
                + "document.getElementById('s').appendChild(b);"
                // One tap opens a tile (the page's own handler opens only the tile that is already selected, so a
                // first tap on another tile just moved the selection): select it first, in the capture phase.
                + "document.querySelectorAll('.tile').forEach(function(t,k){t.addEventListener('click',function(){"
                + "if(typeof select==='function')select(k);},true);});"
                + "})()", null);
    }

    // ---------------------------------------------------------------------------------------------- page text

    private void pushInfo() {
        if (web == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("ready", prefs.getBoolean("game_ready", true) ? "READY TO PLAY" : "CHOOSE YOUR GAME IN GAME FILES");
            o.put("version", "v" + Updater.currentVersion(this));  // the app's versionName (no Gradle BuildConfig here)
            o.put("page", "HOME");
        } catch (JSONException ignored) {
        }
        web.evaluateJavascript("window.setInfo&&setInfo(" + o + ")", null);
    }

    // ---------------------------------------------------------------------------------------------- the page's calls

    private final class Bridge {
        @JavascriptInterface
        public void launchGame() {
            runOnUiThread(() -> openLauncher(null, true));
        }

        @JavascriptInterface
        public void openSection(String id) {
            final String page;
            switch (String.valueOf(id)) {
                case "files": page = "PLAY"; break;  // the PLAY page holds the game files
                case "graphics": page = "GRAPHICS"; break;
                case "controls": page = "CONTROLS"; break;
                case "game": page = "GAME"; break;
                case "saves": page = "SAVES"; break;
                case "logs": page = "LOGS"; break;
                case "updates": page = "UPDATES"; break;
                case "dlc": page = "DLC"; break;
                default: return;
            }
            runOnUiThread(() -> openLauncher(page, false));
        }

        @JavascriptInterface
        public void quit() {
            runOnUiThread(HomeActivity.this::finish);
        }

        // L1 / R1 from HOME: the last and the first settings page (inside a page, L1 / R1 step through the pages).
        @JavascriptInterface
        public void prevPage() {
            runOnUiThread(() -> openLauncher("UPDATES", false));
        }

        @JavascriptInterface
        public void nextPage() {
            runOnUiThread(() -> openLauncher("PLAY", false));
        }
    }

    private void openLauncher(String page, boolean startGame) {
        Intent i = new Intent(this, LauncherActivity.class);
        if (page != null) i.putExtra(LauncherActivity.EXTRA_OPEN_PAGE, page);
        if (startGame) i.putExtra(LauncherActivity.EXTRA_START_GAME, true);
        startActivity(i);
    }

    // ---------------------------------------------------------------------------------------------- controller

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (splash != null) {  // any button closes the start-up screen
            if (e.getAction() == KeyEvent.ACTION_UP) closeSplash();
            return true;
        }
        int to;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: to = e.getKeyCode(); break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: to = KeyEvent.KEYCODE_ENTER; break;
            case KeyEvent.KEYCODE_BUTTON_B: to = KeyEvent.KEYCODE_ESCAPE; break;
            case KeyEvent.KEYCODE_BUTTON_L1: to = KeyEvent.KEYCODE_PAGE_UP; break;
            case KeyEvent.KEYCODE_BUTTON_R1: to = KeyEvent.KEYCODE_PAGE_DOWN; break;
            case KeyEvent.KEYCODE_BUTTON_Y:  // Y: the DLC page
                if (e.getAction() == KeyEvent.ACTION_UP) openLauncher("DLC", false);
                return true;
            default: return super.dispatchKeyEvent(e);  // the phone's back key: onBackPressed
        }
        if (web != null && e.getRepeatCount() == 0) sendKey(to, e.getAction());
        return true;
    }

    private void sendKey(int code, int action) {
        if (web == null) return;
        web.requestFocus();
        web.dispatchKeyEvent(new KeyEvent(action, code));
    }

    // Left stick and hat (some pads send the d-pad as a hat): one key press per push past half way.
    private int stickX, stickY;

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) != 0
                && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > Math.abs(x)) x = hx;
            if (Math.abs(hy) > Math.abs(y)) y = hy;
            int nx = x > 0.5f ? 1 : x < -0.5f ? -1 : 0, ny = y > 0.5f ? 1 : y < -0.5f ? -1 : 0;
            if (nx != stickX) {
                stickX = nx;
                if (nx != 0) press(nx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            }
            if (ny != stickY) {
                stickY = ny;
                if (ny != 0) press(ny > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void press(int code) {
        if (splash != null) return;
        sendKey(code, KeyEvent.ACTION_DOWN);
        sendKey(code, KeyEvent.ACTION_UP);
    }

    @Override
    public void onBackPressed() {
        if (splash != null) {
            closeSplash();
            return;
        }
        finish();
    }

    // ---------------------------------------------------------------------------------------------- pictures

    private File imagesDir() { return new File(getFilesDir(), "launcher_images"); }

    /** images/<name> for the page: a picture chosen on the phone wins (bg_home.jpg = the background of GAME > Look),
     *  else the file in the app's own assets/launcher/images (a personal build may carry them). */
    private WebResourceResponse picture(String name) {
        try {
            if (!name.matches("[A-Za-z0-9_]+\\.(jpg|jpeg|png|webp)")) return notFound();
            File f = name.equals("bg_home.jpg") ? new File(getFilesDir(), "launcher_background") : new File(imagesDir(), name);
            if (!f.isFile()) return null;  // no picture of the player's own: the app's assets/launcher/images, if any
            return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** First start (or after "Remove all tile pictures"): the player's own picture zip in Download, if it is there
     *  and the app may read it (All files access), is copied in. */
    private void importPicturesFromDownload() {
        File[] have = imagesDir().listFiles();
        if (have != null && have.length > 0) return;
        new Thread(() -> {
            File zip = findPictureZip();
            if (zip == null) {
                Log.i(TAG, "[home] no launcher picture zip found in Download (looked for " + PICTURE_ZIP + " or a zip with \"launcher\" in its name)");
                return;
            }
            int n = unpackPictures(zip);
            Log.i(TAG, "[home] " + n + " pictures from " + zip);
            if (n > 0) handler.post(() -> { if (web != null) web.reload(); });
        }, "home-pictures").start();
    }

    /** WWE2K15_Launcher_WebView.zip in Download (or one folder below), else any zip there with "launcher" in its
     *  name (e.g. a browser's "WWE2K15_Launcher_WebView (1).zip"). */
    private File findPictureZip() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File exact = new File(dl, PICTURE_ZIP);
        if (exact.canRead()) return exact;
        File best = null;
        java.util.List<File> dirs = new java.util.ArrayList<>();
        dirs.add(dl);
        File[] subs = dl.listFiles(File::isDirectory);
        if (subs != null) java.util.Collections.addAll(dirs, subs);
        for (File d : dirs) {
            File[] zs = d.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip") && n.toLowerCase(Locale.ROOT).contains("launcher"));
            if (zs == null) continue;
            for (File z : zs) if (z.canRead() && (best == null || z.lastModified() > best.lastModified())) best = z;
        }
        if (best == null) Log.i(TAG, "[home] Download readable: " + dl.canRead());
        return best;
    }

    private int unpackPictures(File zip) {
        Pattern names = Pattern.compile("(?:.*/)?(bg_home|tile_(?:play|files|graphics|controls|game|saves|logs|updates))\\.(?:jpe?g|png|webp)",
                Pattern.CASE_INSENSITIVE);
        int n = 0;
        byte[] buf = new byte[65536];
        imagesDir().mkdirs();
        try (InputStream in = new FileInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                Matcher m = names.matcher(e.getName());
                if (!m.matches()) continue;
                String base = m.group(1).toLowerCase(Locale.ROOT);
                File dest = base.equals("bg_home") ? new File(getFilesDir(), "launcher_background") : new File(imagesDir(), base + ".jpg");
                long total = 0;
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    int r;
                    while ((r = z.read(buf)) > 0) {
                        total += r;
                        if (total > 20L * 1024 * 1024) throw new IOException("picture too big: " + e.getName());
                        out.write(buf, 0, r);
                    }
                }
                ++n;
            }
        } catch (IOException ex) {
            Log.w(TAG, "home pictures: " + ex);
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------- start-up screen

    private FrameLayout splash;

    /** 3 seconds, blinking TAP TO PLAY: the player's own start-up picture (GAME > Look) or a drawn red screen. */
    private void showSplash(FrameLayout root) {
        splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFF140002);
        splash.setClickable(true);
        File own = new File(getFilesDir(), "launcher_splash");
        android.graphics.Bitmap pic = own.isFile() ? decode(own) : null;
        if (pic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(pic);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            splash.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout logo = new LinearLayout(this);
            logo.setOrientation(LinearLayout.VERTICAL);
            logo.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView big = new TextView(this);
            big.setText("WWE 2K15");
            big.setTextSize(64);
            big.setTextColor(Color.WHITE);
            big.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            big.setShadowLayer(24, 0, 0, 0xFFE3141C);
            logo.addView(big);
            TextView by = new TextView(this);
            by.setText("MADE BY SHADOW XAVIER");
            by.setTextSize(14);
            by.setTextColor(0xFFFFE6E6);
            by.setLetterSpacing(0.25f);
            by.setGravity(Gravity.CENTER);
            logo.addView(by);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            lp.bottomMargin = 80;
            splash.addView(logo, lp);
        }
        TextView tap = new TextView(this);
        tap.setText("TAP TO PLAY");
        tap.setTextSize(30);
        tap.setTextColor(Color.WHITE);
        tap.setLetterSpacing(0.12f);
        tap.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        tap.setShadowLayer(14, 0, 0, 0xFFE3141C);
        tap.setPadding(60, 14, 60, 14);
        tap.setBackgroundColor(0x99000000);
        splash.addView(tap, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0.25f);
        blink.setDuration(600);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tap.startAnimation(blink);
        splash.setOnClickListener(v -> closeSplash());
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        handler.postDelayed(this::closeSplash, 3000);
    }

    private void closeSplash() {
        final FrameLayout s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (s.getParent() instanceof FrameLayout) ((FrameLayout) s.getParent()).removeView(s);
            if (web != null) web.requestFocus();
        }).start();
    }

    private android.graphics.Bitmap decode(File f) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }
}
