// WWE 2K15 Android: the launcher. Its layout and look are the WWE '13 Android port's launcher
// (github.com/dzepdzep/wwe13-recomp-android, BSD 3-Clause, Copyright (c) 2026 dzepdzep): the menu on the left, the
// chosen page on the right, over a big red X, with slanted highlights like the game's menus. In WWE 2K15 red.
// Pages: PLAY (game files, title update, DLC), GRAPHICS, GAME, CONTROLS, SAVES, UPDATES.
// The settings reach the game as command-line arguments in launch_options.txt (WWE2K15Activity reads it).
package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LauncherActivity extends Activity {
    private static final int REQ_ISO = 1;
    private static final int REQ_PACKAGE = 2;
    private static final int REQ_DRIVER = 8;
    private static final int REQ_PICK_ISO = 9;
    private static final int REQ_PICK_FOLDER = 10;
    private static final int REQ_BACKGROUND = 11;
    private static final int REQ_SPLASH = 12;
    private static final int REQ_TILE_IMAGE = 13;
    private static final int REQ_PICTURE_ZIP = 14;

    // Resolution: the draw height on the phone (the SDK draws at 720p x resolution_scale, or lower with
    // gpu_native_truth_draw_height), as in the WWE 2K17 port.
    private static final String[] RES_LABELS = {"360p (fastest)", "480p", "540p", "720p (original)", "1080p", "1440p (sharpest)"};
    private static final int[] RES_HEIGHTS = {360, 480, 540, 720, 1080, 1440};
    // The game moves a fixed step per frame, so below 60 FPS it plays in slow motion. "30 FPS at real speed" uses the
    // game's own 30-updates-a-second timing instead (WWE 2K14 port's fix, port/src/framerate_hook.cpp step30).
    private static final String[] FPS_LABELS = {
            "Automatic (recommended): 60 FPS, real-speed 30 FPS if the phone can't keep up",
            "60 FPS everywhere (can slow down on weaker phones)",
            "30 FPS at real speed (weaker phones, cooler, longer battery)",
            "Original: 60 FPS matches, 30 FPS entrances"};
    // port/src/android_gpu_profile.h: the WWE 2K14 port's NATIVE renderer, or the SDK's standard Xenos emulation.
    private static final String[] RENDERER_LABELS = {"NATIVE (from WWE 2K14, faster)", "Standard (safest, if NATIVE draws something wrong)"};

    // WWE 2K15 look: the WWE '13 launcher's layout in deep red, the game's bright red, white bold capitals.
    private static final int BG = 0xFF1A0306;
    private static final int CARD = 0xE62A070B;
    private static final int ACCENT = 0xFFE3141C;
    private static final int TEXT = 0xFFF7F2F2;
    private static final int DIM = 0xFFC9A3A7;
    private static final int BUTTON = 0xFF45101A;

    private SharedPreferences prefs;
    private File root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private Button playButton;
    private LinearLayout packageList;
    private LinearLayout dlcList;
    private TextView dlcCount;
    private LinearLayout backupList;
    private TextView logSessionText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        GameFiles.init(root);
        installBundledDrivers();

        boolean openedAsSettings = getIntent() != null && getIntent().getComponent() != null
                && getIntent().getComponent().getClassName().endsWith("SettingsAlias");
        // Opened from the WebView home screen (HomeActivity): one page, or straight into the game.
        String openPage = getIntent() != null ? getIntent().getStringExtra(EXTRA_OPEN_PAGE) : null;
        boolean startNow = getIntent() != null && getIntent().getBooleanExtra(EXTRA_START_GAME, false);
        fromHome = openPage != null || startNow;
        if (!fromHome && !openedAsSettings && prefs.getBoolean("skip_launcher", false) && gameReady()) {
            startSoon();
            return;
        }
        buildUi();
        if (fromHome) {
            openFromHome(openPage, startNow);
            return;
        }
        if (savedInstanceState == null) showSplash();
    }

    static final String EXTRA_OPEN_PAGE = "open_page";    // "PLAY" (game files), "GRAPHICS", "CONTROLS", "GAME", ...
    static final String EXTRA_START_GAME = "start_game";  // true: start the game at once (else its PLAY page)
    private boolean fromHome;

    private void openFromHome(String page, boolean startNow) {
        if (startNow) {
            if (gameReady()) {
                startGame();  // finishes this screen when the game starts
                return;
            }
            page = "PLAY";
        }
        showTab(page != null ? page : "PLAY");
    }

    // ------------------------------------------------------------------------------------------ start-up screen

    private File splashFile() { return new File(getFilesDir(), "launcher_splash"); }

    /**
     * The start-up screen, for 3 seconds over the home screen: the player's own picture (GAME > Look) or the drawn one
     * (blood red, WWE 2K15, made by Shadow Xavier), with a blinking TAP TO PLAY in the middle. A tap closes it early.
     */
    private void showSplash() {
        final FrameLayout content = findViewById(android.R.id.content);
        if (content == null) return;
        final FrameLayout splash = new FrameLayout(this);
        splash.setClickable(true);
        splash.setFocusable(true);
        HomeBackground bg = new HomeBackground();
        bg.dimBottom = false;
        splash.setBackground(bg);
        android.graphics.Bitmap own = loadPicture(splashFile());
        if (own != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(own);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            splash.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout logo = new LinearLayout(this);
            logo.setOrientation(LinearLayout.VERTICAL);
            logo.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView big = gradientText("WWE 2K15", 72, new int[] {0xFFFFFFFF, 0xFFFFD0D0, 0xFFFF2A2A, 0xFF8A0008});
            big.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            big.setShadowLayer(dp(18), 0, dp(2), 0xEE000000);
            logo.addView(big);
            TextView by = text("MADE BY SHADOW XAVIER", 14, 0xFFFFE6E6);
            by.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            by.setLetterSpacing(0.25f);
            by.setShadowLayer(dp(4), 0, 0, 0xFF000000);
            logo.addView(by);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            lp.bottomMargin = dp(40);
            splash.addView(logo, lp);
        }
        TextView tap = text("TAP TO PLAY", 30, Color.WHITE);
        tap.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        tap.setLetterSpacing(0.12f);
        tap.setShadowLayer(dp(10), 0, 0, 0xFFE3141C);
        tap.setGravity(Gravity.CENTER);
        tap.setPadding(dp(28), dp(6), dp(28), dp(6));
        tap.setBackgroundColor(0x99000000);
        splash.addView(tap, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0.25f);
        blink.setDuration(600);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tap.startAnimation(blink);
        content.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        final Runnable close = () -> {
            if (splash.getParent() == null || splash.getTag() != null) return;
            splash.setTag("closing");
            splash.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                tap.clearAnimation();
                content.removeView(splash);
                showHome();
            }).start();
        };
        splash.setOnClickListener(v -> close.run());
        splash.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) close.run();
            return true;
        });
        splash.requestFocus();
        handler.postDelayed(close, 3000);
    }

    private void setPictureFile(Uri uri, File dest) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    // "Start the game directly": a short screen first, where any button or a tap opens the settings instead.
    private boolean startPending;
    private final Runnable startRunnable = () -> { if (startPending) { startPending = false; startGame(); } };

    private void startSoon() {
        TextView t = text("WWE 2K15\n\nPRESS ANY BUTTON OR TAP FOR SETTINGS", 18, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        t.setBackground(new XBackground());
        t.setOnClickListener(v -> openSettingsInstead());
        setContentView(t);
        startPending = true;
        handler.postDelayed(startRunnable, 2000);
    }

    private void openSettingsInstead() {
        if (!startPending) return;
        startPending = false;
        handler.removeCallbacks(startRunnable);
        buildUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (startPending) {
            openSettingsInstead();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------------------------------ bundled drivers

    /** The Turnip driver packages in assets/drivers/*.zip are unpacked into files/drivers/<name>/ once. */
    private void installBundledDrivers() {
        int bundled = 1;  // bump when the bundled drivers change
        if (prefs.getInt("bundled_drivers", 0) >= bundled) return;
        try {
            String[] zips = getAssets().list("drivers");
            if (zips != null) for (String z : zips) {
                if (!z.endsWith(".zip")) continue;
                File dest = new File(getFilesDir(), "drivers/" + z.substring(0, z.length() - 4));
                GameFiles.deleteTree(dest);
                dest.mkdirs();
                try (ZipInputStream zin = new ZipInputStream(getAssets().open("drivers/" + z))) {
                    unzipFlat(zin, dest);
                }
            }
            prefs.edit().putInt("bundled_drivers", bundled).apply();
        } catch (Exception e) {
            // The game still runs with the phone's own driver.
        }
    }

    private static boolean unzipFlat(ZipInputStream zin, File dest) throws Exception {
        boolean hasLibrary = false;
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = e.getName().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            if (name.isEmpty()) continue;
            if (name.endsWith(".so")) hasLibrary = true;
            try (OutputStream os = new FileOutputStream(new File(dest, name))) {
                GameFiles.copyStream(zin, os);
            }
        }
        return hasLibrary;
    }

    // ------------------------------------------------------------------------------------------ UI

    private void buildUi() {
        tabs.clear();
        tabButtons.clear();
        // WWE 2K-style home: a title area, the HOME heading with L1 / R1, two rows of big tiles (the selected one lit in
        // red, the others dark), and a button-hint bar. A tile opens its page; B / back comes back HOME.
        FrameLayout rootView = new FrameLayout(this);
        homeBg = new HomeBackground();
        homeBg.picture = loadBackgroundPicture();
        rootView.setBackground(homeBg);
        // The big "2K15" mark and the credit, top right of the home screen (original lettering).
        homeMark = new LinearLayout(this);
        homeMark.setOrientation(LinearLayout.VERTICAL);
        homeMark.setGravity(Gravity.END);
        TextView mark = gradientText("2K15", 76, new int[] {0xFFFFFFFF, 0xFFFFD0D0, 0xFFFF2A2A, 0xFF8A0008});
        mark.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        mark.setShadowLayer(dp(18), 0, dp(2), 0xEE000000);
        mark.setLetterSpacing(-0.02f);
        homeMark.addView(mark);
        TextView credit = text("MADE BY SHADOW XAVIER", 10, 0xFFFFE6E6);
        credit.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        credit.setLetterSpacing(0.25f);
        credit.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        credit.setPadding(0, 0, dp(6), 0);
        homeMark.addView(credit);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        mlp.setMargins(0, dp(10), dp(36), 0);
        rootView.addView(homeMark, mlp);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(36), dp(18), dp(36), dp(10));
        rootView.addView(column, new FrameLayout.LayoutParams(-1, -1));
        columnView = column;
        // The home screen itself: the player's own WebView design (assets/launcher/index.html, made by Shadow Xavier).
        // The WebView home screen is its own activity now (HomeActivity); this screen keeps the drawn one for the
        // settings shortcut (SettingsAlias) only.
        homeWeb = null;

        // -- HOME
        homeView = new LinearLayout(this);
        homeView.setOrientation(LinearLayout.VERTICAL);
        TextView title = gradientText("WWE 2K15", 44, new int[] {0xFFFFFFFF, 0xFFFFE0E0, 0xFFFF3B3B});
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        title.setShadowLayer(dp(16), 0, 0, 0xFFE3141C);
        title.setLetterSpacing(0.02f);
        homeView.addView(title);
        TextView sub = text("ANDROID  ·  RECOMPILED", 13, 0xFFFFD6D6);
        sub.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        sub.setLetterSpacing(0.3f);
        homeView.addView(sub);
        homeStatus = text("", 13, DIM);
        homeStatus.setPadding(0, dp(4), 0, 0);
        homeView.addView(homeStatus);
        View grow = new View(this);
        homeView.addView(grow, new LinearLayout.LayoutParams(1, 0, 1f));
        homeView.addView(heading("HOME"));
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setRowCount(2);
        grid.setColumnCount(4);
        android.widget.HorizontalScrollView tilesScroll = new android.widget.HorizontalScrollView(this);
        tilesScroll.setHorizontalScrollBarEnabled(false);
        tilesScroll.setClipChildren(false);
        grid.setClipChildren(false);
        tilesScroll.addView(grid);
        homeView.addView(tilesScroll, new LinearLayout.LayoutParams(-1, -2));
        String[][] tiles = {{"PLAY", "PLAY", "\u25B6"}, {"GAME FILES", "PLAY", "\u25A4"}, {"GRAPHICS", "GRAPHICS", "\u25C8"},
                {"CONTROLS", "CONTROLS", "\u271C"}, {"GAME", "GAME", "\u2605"}, {"SAVES", "SAVES", "\u21BB"},
                {"LOGS", "LOGS", "\u2630"}, {"UPDATES", "UPDATES", "\u2B07"}};
        firstTile = null;
        // Two rows of tiles must fit under the title on a phone held sideways (about 390 dp high): size them to the screen.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenDp = (int) (Math.min(dm.heightPixels, dm.widthPixels) / dm.density);
        int tileH = Math.max(64, Math.min(108, (screenDp - 240) / 2));
        for (String[] t : tiles) {
            final String page = t[1];
            View tile = t[0].equals("PLAY") ? tile(t[0], t[2], v -> { if (gameReady()) startGame(); else showTab("PLAY"); })
                    : tile(t[0], t[2], v -> showTab(page));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = dp(tileH * 230 / 108);
            lp.height = dp(tileH);
            lp.setMargins(0, 0, dp(12), dp(12));
            grid.addView(tile, lp);
            if (firstTile == null) firstTile = tile;
        }
        column.addView(homeView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // -- a page (opened from a tile)
        pageView = new LinearLayout(this);
        pageView.setOrientation(LinearLayout.VERTICAL);
        pageTitle = heading("");
        pageView.addView(pageTitle);
        ScrollView scroll = new ScrollView(this);
        contentScroll = scroll;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), dp(8), dp(16));
        scroll.addView(page);
        pageView.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        column.addView(pageView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // -- button hints, version
        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        hints.setPadding(0, dp(6), 0, 0);
        hintText = text("", 13, TEXT);
        hintText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        hintText.setLetterSpacing(0.06f);
        hints.addView(hintText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ver = text("v" + Updater.currentVersion(this) + "\nANDROID PORT", 11, TEXT);
        ver.setGravity(Gravity.END);
        ver.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        hints.addView(ver);
        column.addView(hints);

        // ---- PLAY
        currentTab = "PLAY";
        updateBox = new LinearLayout(this);
        updateBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(updateBox);
        tag(updateBox);
        if (prefs.getBoolean("check_updates", true)) Updater.check(this, true, this::showUpdate);

        playButton = button("PLAY", v -> startGame());
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        playButton.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        playButton.setPadding(dp(24), dp(18), dp(24), dp(18));
        StateListDrawable playBg = new StateListDrawable();
        playBg.addState(new int[]{android.R.attr.state_focused}, rounded(0xFFFF2A30));
        playBg.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFF9E1015));
        playBg.addState(new int[]{}, rounded(ACCENT));
        playButton.setBackground(playBg);
        page.addView(playButton, matchWidth(dp(6)));
        tag(playButton);
        CheckBox skip = checkbox("Start the game directly next time (press any button while it starts to come back here)",
                prefs.getBoolean("skip_launcher", false), c -> prefs.edit().putBoolean("skip_launcher", c).apply());
        page.addView(skip);
        tag(skip);

        // Game files
        LinearLayout files = section(page, "Game files");
        statusText = text("", 15, TEXT);
        files.addView(statusText);
        LinearLayout fileButtons = row();
        fileButtons.addView(button("Choose disc image (.iso)…", v -> chooseGame(false)));
        fileButtons.addView(button("Choose game folder…", v -> chooseGame(true)));
        files.addView(fileButtons);
        LinearLayout fileButtons2 = row();
        fileButtons2.addView(button("Add title update or DLC…", v -> pick(REQ_PACKAGE, true)));
        fileButtons2.addView(button("Copy an .iso into the app…", v -> pick(REQ_ISO, false)));
        files.addView(fileButtons2);
        files.addView(text("Use your own WWE 2K15 Xbox 360 game: a disc image (.iso) or an unpacked game folder (the one "
                + "with default.xex), played where it is. Then add Title Update 4 (the tu00000004_00000000 file) and any DLC. "
                + "\"Copy an .iso into the app\" is for phones that block reading the game in place (about 8 GB).", 13, DIM));
        packageList = new LinearLayout(this);
        packageList.setOrientation(LinearLayout.VERTICAL);
        files.addView(packageList);

        // ---- DLC
        currentTab = "DLC";
        LinearLayout dlc = section(page, "DLC");
        dlc.addView(text("Add your own WWE 2K15 DLC packages (Xbox 360 content files, usually in a 00000002 folder). "
                + "You can pick several at once. The game installs them the next time it starts.", 13, DIM));
        LinearLayout dlcButtons = row();
        dlcButtons.addView(button("Add DLC\u2026", v -> pick(REQ_PACKAGE, true)));
        dlc.addView(dlcButtons);
        dlcCount = text("", 15, TEXT);
        dlc.addView(dlcCount);
        dlcList = new LinearLayout(this);
        dlcList.setOrientation(LinearLayout.VERTICAL);
        dlc.addView(dlcList);

        // ---- GRAPHICS
        currentTab = "GRAPHICS";
        LinearLayout settings = section(page, "Graphics");
        settings.addView(spinnerRow("Resolution", RES_LABELS, prefs.getInt("resolution", 3),
                i -> prefs.edit().putInt("resolution", i).apply()));
        settings.addView(spinnerRow("Frame rate", FPS_LABELS, prefs.getInt("frame_rate2", 0),
                i -> prefs.edit().putInt("frame_rate2", i).apply()));
        settings.addView(spinnerRow("Renderer", RENDERER_LABELS, prefs.getInt("renderer", 0),
                i -> prefs.edit().putInt("renderer", i).apply()));
        final List<String> driverIds = driverIds();
        List<String> driverLabels = new ArrayList<>();
        for (String id : driverIds) driverLabels.add(driverLabel(id));
        settings.addView(spinnerRow("Graphics driver", driverLabels.toArray(new String[0]),
                Math.max(0, driverIds.indexOf(currentDriver())),
                i -> prefs.edit().putString("driver_name", driverIds.get(i)).apply()));
        settings.addView(button("Add GPU driver (.zip)…", v -> pick(REQ_DRIVER, false)), wrap(dp(4)));
        settings.addView(checkbox("Sharper upscaling (SGSR)", prefs.getBoolean("upscaler", false),
                c -> prefs.edit().putBoolean("upscaler", c).apply()));
        settings.addView(checkbox("Frame generation: an in-between picture for every frame (smoother, e.g. 30 shows as 60; "
                + "controls stay at the game's speed, fast motion can smear a little)", prefs.getBoolean("frame_gen", false),
                c -> prefs.edit().putBoolean("frame_gen", c).apply()));
        settings.addView(checkbox("Stretch the picture to fill the screen", prefs.getBoolean("stretch", false),
                c -> prefs.edit().putBoolean("stretch", c).apply()));
        settings.addView(checkbox("Show an FPS counter in the game", prefs.getBoolean("show_fps", false),
                c -> prefs.edit().putBoolean("show_fps", c).apply()));
        settings.addView(text("The first start builds the game's graphics shaders while you play, so the first menus and "
                + "matches can stutter. Later starts reuse them.", 13, DIM));

        // ---- GAME
        currentTab = "GAME";
        LinearLayout look = section(page, "Look");
        look.addView(text("Put a picture of your own behind the home screen (it stays on this phone only).", 13, DIM));
        LinearLayout lookButtons = row();
        lookButtons.addView(button("Choose background picture…", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_BACKGROUND);
        }));
        lookButtons.addView(button("Remove picture", v -> {
            backgroundFile().delete();
            if (homeBg != null) {
                homeBg.picture = null;
                homeBg.invalidateSelf();
            }
        }));
        look.addView(lookButtons);
        look.addView(text("Start-up picture: shown for 3 seconds with TAP TO PLAY when the app opens (stays on this phone only).", 13, DIM));
        LinearLayout splashButtons = row();
        splashButtons.addView(button("Choose start-up picture…", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_SPLASH);
        }));
        splashButtons.addView(button("Preview", v -> showSplash()));
        splashButtons.addView(button("Remove", v -> splashFile().delete()));
        look.addView(splashButtons);
        look.addView(text("Tile pictures: on HOME, press and hold a tile to choose its picture; hold the WWE 2K15 title for the background.", 13, DIM));
        look.addView(text("Or import all pictures at once from your own launcher zip (images/bg_home.jpg, images/tile_play.jpg, tile_files, tile_graphics, tile_controls, tile_game, tile_saves, tile_logs, tile_updates).", 13, DIM));
        look.addView(button("Import pictures from a zip\u2026", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICTURE_ZIP);
        }), wrap(dp(4)));
        look.addView(button("Remove all tile pictures", v -> {
            File[] fs = tileImagesDir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
            if (homeWeb != null) homeWeb.reload();
        }), wrap(dp(4)));
        settings = section(page, "Game");
        settings.addView(checkbox("Run the game on the phone's fast CPU cores (recommended)", prefs.getBoolean("big_cores", true),
                c -> prefs.edit().putBoolean("big_cores", c).apply()));
        settings.addView(checkbox("Universe: allow any show in SHOW EDIT (experimental, use a copy of your save)",
                prefs.getBoolean("universe_free", false), c -> prefs.edit().putBoolean("universe_free", c).apply()));
        settings.addView(text("Extra arguments for the game (one per line, for testing):", 13, DIM));
        final android.widget.EditText extra = new android.widget.EditText(this);
        extra.setText(prefs.getString("extra_args", ""));
        extra.setTextColor(TEXT);
        extra.setHintTextColor(DIM);
        extra.setHint("--log_level=info");
        extra.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        extra.setBackground(rounded(BUTTON));
        extra.setPadding(dp(12), dp(8), dp(12), dp(8));
        extra.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString("extra_args", s.toString()).apply();
            }
        });
        settings.addView(extra, matchWidth(dp(4)));

        // ---- CONTROLS
        currentTab = "CONTROLS";
        settings = section(page, "Touch controls");
        settings.addView(spinnerRow("Touch controls", new String[] {"Automatic (hidden while a controller is connected)", "Always on", "Off"},
                prefs.getInt("touch_mode", 0), i -> prefs.edit().putInt("touch_mode", i).apply()));
        settings.addView(spinnerRow("Touch controls opacity", new String[] {"Light (30%)", "Medium (50%)", "Strong (75%)"},
                prefs.getInt("touch_opacity", 1), i -> prefs.edit().putInt("touch_opacity", i).apply()));
        settings.addView(spinnerRow("Touch button size", new String[] {"Small", "Medium", "Large"},
                prefs.getInt("touch_size", 1), i -> prefs.edit().putInt("touch_size", i).apply()));
        settings.addView(button("Edit touch layout…", v -> startActivity(new Intent(this, TouchLayoutActivity.class))), wrap(dp(4)));
        settings.addView(checkbox("Vibrate when a touch button is pressed", prefs.getBoolean("touch_vibrate", true),
                c -> prefs.edit().putBoolean("touch_vibrate", c).apply()));
        settings.addView(text("Touch controls: tap RUN to switch running on or off (or push the stick past its ring). HIDE / SHOW "
                + "in the game's top-right corner hides the buttons and brings them back; hold it (or use Edit touch layout) to move and resize them.", 13, DIM));

        // ---- LOGS (like the user's UFC Undisputed 3 launcher: last session, view, share, copy, clear)
        currentTab = "LOGS";
        LinearLayout last = section(page, "Last session");
        logSessionText = text("", 15, TEXT);
        last.addView(logSessionText);
        last.addView(listItem("Open log", "View", this::viewLog));
        last.addView(listItem("Share logs", "Share", this::shareLogs));
        last.addView(listItem("Copy logs", "Copy", this::copyLogs));
        last.addView(listItem("Save logs to Downloads", "Save", this::saveLogsToDownloads));
        last.addView(text("When something breaks: Share (or Copy) the logs and send them. They hold the game's log, "
                + "Android's log for the app (it shows why the game stopped before its own log started) and the settings it "
                + "started with.", 13, DIM));
        LinearLayout manage = section(page, "Manage");
        manage.addView(listItem("Clear all logs", "Clear", () -> confirm("Delete all the game's logs?", () -> {
            File[] logs = new File(root, "logs").listFiles(File::isFile);
            int n = 0;
            if (logs != null) for (File f : logs) if (f.delete()) ++n;
            toastDialog(n + " logs deleted.");
            refresh();
        })));

        // ---- UPDATES
        currentTab = "UPDATES";
        LinearLayout upd = section(page, "Updates");
        upd.addView(checkbox("Check for updates when the launcher opens", prefs.getBoolean("check_updates", true),
                c -> prefs.edit().putBoolean("check_updates", c).apply()));
        upd.addView(button("Check for updates now", v -> Updater.check(this, false, this::showUpdate)), wrap(dp(4)));

        // ---- SAVES
        currentTab = "SAVES";
        LinearLayout saves = section(page, "Saves");
        saves.addView(checkbox("Back up my saves before each start (the newest 20 are kept)", prefs.getBoolean("auto_backup", true),
                c -> prefs.edit().putBoolean("auto_backup", c).apply()));
        saves.addView(button("Back up my saves now", v -> {
            String err = GameFiles.backupSaves(false);
            toastDialog(err.isEmpty() ? "Saves backed up." : err);
            refresh();
        }), wrap(dp(4)));
        backupList = new LinearLayout(this);
        backupList.setOrientation(LinearLayout.VERTICAL);
        saves.addView(backupList);

        setContentView(rootView);
        refresh();
        showHome();
    }

    // ------------------------------------------------------------------------------------------ pages
    private final java.util.LinkedHashMap<String, List<View>> tabs = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, Button> tabButtons = new java.util.HashMap<>();
    private String currentTab = "PLAY", shownTab = "PLAY";
    private LinearLayout navItems;
    private ScrollView contentScroll;

    private void tag(View v) {
        List<View> l = tabs.get(currentTab);
        if (l == null) tabs.put(currentTab, l = new ArrayList<>());
        l.add(v);
    }

    private static final String[] PAGE_ORDER = {"PLAY", "DLC", "GRAPHICS", "CONTROLS", "GAME", "SAVES", "LOGS", "UPDATES"};
    private static final java.util.Map<String, String> PAGE_TITLES = new java.util.HashMap<String, String>() {{
        put("PLAY", "GAME FILES"); put("DLC", "DLC"); put("GRAPHICS", "GRAPHICS"); put("CONTROLS", "CONTROLS"); put("GAME", "GAME");
        put("SAVES", "SAVES"); put("LOGS", "LOGS"); put("UPDATES", "UPDATES");
    }};
    private LinearLayout homeView, pageView;
    private LinearLayout pageTitle;
    private TextView homeStatus, hintText;
    private View firstTile, lastTile;
    private boolean onHome = true;
    private LinearLayout homeMark;
    private LinearLayout columnView;
    private android.webkit.WebView homeWeb;
    private String pendingTileImage;

    private File tileImagesDir() { return new File(getFilesDir(), "launcher_images"); }

    /** The home screen as a WebView. Pictures: images/bg_home.jpg = the background picture (GAME > Look),
     *  images/tile_<id>.jpg = the player's own tile pictures (long-press a tile); none are shipped with the app. */
    private android.webkit.WebView makeHomeWeb() {
        try {
            android.webkit.WebView w = new android.webkit.WebView(this);
            w.setBackgroundColor(0xFF140002);
            android.webkit.WebSettings ws = w.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
            ws.setAllowFileAccess(false);
            ws.setMediaPlaybackRequiresUserGesture(true);
            w.setVerticalScrollBarEnabled(false);
            w.setHorizontalScrollBarEnabled(false);
            w.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView v,
                        android.webkit.WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if (!"appassets.androidplatform.net".equals(u.getHost())) return notFound();
                    String path = u.getPath() == null ? "" : u.getPath();
                    try {
                        if (path.startsWith("/launcher/images/")) {
                            String name = path.substring("/launcher/images/".length());
                            if (name.contains("/") || name.contains("..")) return notFound();
                            File f = name.equals("bg_home.jpg") ? backgroundFile() : new File(tileImagesDir(), name);
                            if (!f.isFile()) return notFound();
                            return new android.webkit.WebResourceResponse("image/jpeg", null, new java.io.FileInputStream(f));
                        }
                        if (path.startsWith("/launcher/") && !path.contains("..")) {
                            String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                                    : path.endsWith(".js") ? "application/javascript" : "application/octet-stream";
                            return new android.webkit.WebResourceResponse(mime, "UTF-8", getAssets().open(path.substring(1)));
                        }
                    } catch (java.io.IOException e) {
                        return notFound();
                    }
                    return notFound();
                }

                @Override
                public boolean shouldOverrideUrlLoading(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                    return true;  // the home screen never navigates away
                }

                @Override
                public void onPageFinished(android.webkit.WebView v, String url) {
                    pushHomeInfo(null);
                }
            });
            w.addJavascriptInterface(new HomeBridge(), "Android");
            w.loadUrl("https://appassets.androidplatform.net/launcher/index.html");
            return w;
        } catch (Throwable t) {  // no WebView on this phone: the drawn home screen stays
            android.util.Log.w("wwe2k15", "home WebView: " + t);
            return null;
        }
    }

    private static android.webkit.WebResourceResponse notFound() {
        return new android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new java.util.HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
    }

    private void pushHomeInfo(String toast) {
        if (homeWeb == null) return;
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("ready", gameReady() ? "READY TO PLAY" : "CHOOSE YOUR GAME IN GAME FILES");
            o.put("version", "v" + Updater.currentVersion(this));
            if (toast != null) o.put("toast", toast);
        } catch (org.json.JSONException ignored) {
        }
        homeWeb.evaluateJavascript("window.setInfo&&setInfo(" + o + ")", null);
    }

    private final class HomeBridge {
        @android.webkit.JavascriptInterface
        public void launchGame() {
            runOnUiThread(() -> { if (gameReady()) startGame(); else showTab("PLAY"); });
        }

        @android.webkit.JavascriptInterface
        public void openSection(String id) {
            final String page;
            switch (String.valueOf(id)) {
                case "files": page = "PLAY"; break;
                case "graphics": page = "GRAPHICS"; break;
                case "controls": page = "CONTROLS"; break;
                case "game": page = "GAME"; break;
                case "saves": page = "SAVES"; break;
                case "logs": page = "LOGS"; break;
                case "updates": page = "UPDATES"; break;
                default: page = "PLAY";
            }
            runOnUiThread(() -> showTab(page));
        }

        @android.webkit.JavascriptInterface
        public void quit() { runOnUiThread(LauncherActivity.this::finish); }

        @android.webkit.JavascriptInterface
        public void prevPage() { runOnUiThread(() -> showTab(PAGE_ORDER[PAGE_ORDER.length - 1])); }

        @android.webkit.JavascriptInterface
        public void nextPage() { runOnUiThread(() -> showTab(PAGE_ORDER[0])); }

        @android.webkit.JavascriptInterface
        public void pickImage(String id) {
            if (id == null || !id.matches("[a-z]{1,12}")) return;
            runOnUiThread(() -> {
                pendingTileImage = id;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_TILE_IMAGE);
            });
        }
    }

    /** The player's own pictures for HOME, from a zip they pick (any folder; file names as in their WebView design). */
    private void importPictureZip(Uri uri) {
        int n = 0;
        java.util.regex.Pattern names = java.util.regex.Pattern.compile("(?:.*/)?(bg_home|tile_(?:play|files|graphics|controls|game|saves|logs|updates))\\.(?:jpe?g|png|webp)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        try (InputStream in = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            tileImagesDir().mkdirs();
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                java.util.regex.Matcher m = names.matcher(e.getName());
                if (!m.matches()) continue;
                String base = m.group(1).toLowerCase(java.util.Locale.ROOT);
                File dest = base.equals("bg_home") ? backgroundFile() : new File(tileImagesDir(), base + ".jpg");
                long total = 0;
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        total += r;
                        if (total > 20L * 1024 * 1024) throw new java.io.IOException("picture too big: " + e.getName());
                        out.write(buf, 0, r);
                    }
                }
                ++n;
            }
        } catch (Exception ex) {
            toastDialog("Could not read that zip: " + ex.getMessage());
            return;
        }
        if (n == 0) {
            toastDialog("No pictures found in that zip (expected names like images/tile_play.jpg and images/bg_home.jpg).");
            return;
        }
        if (homeBg != null) {
            homeBg.picture = loadBackgroundPicture();
            homeBg.invalidateSelf();
        }
        if (homeWeb != null) homeWeb.reload();
        final int count = n;
        showHome();
        handler.postDelayed(() -> pushHomeInfo(count + " pictures added"), 600);
    }

    private void setTileImage(Uri uri) {
        String id = pendingTileImage;
        pendingTileImage = null;
        if (id == null) return;
        try {
            File dest;
            if (id.equals("bg")) {
                dest = backgroundFile();
            } else {
                tileImagesDir().mkdirs();
                dest = new File(tileImagesDir(), "tile_" + id + ".jpg");
            }
            setPictureFile(uri, dest);
            if (id.equals("bg") && homeBg != null) {
                homeBg.picture = loadBackgroundPicture();
                homeBg.invalidateSelf();
            }
        } catch (Exception e) {
            toastDialog("Could not read that picture: " + e.getMessage());
            return;
        }
        if (homeWeb != null) homeWeb.reload();
    }
    private HomeBackground homeBg;

    /** Text filled with a top-to-bottom colour gradient (white to red) for the titles. */
    private TextView gradientText(String s, int sp, final int[] colors) {
        TextView t = new TextView(this) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                getPaint().setShader(new android.graphics.LinearGradient(0, getPaddingTop(), 0, h - getPaddingBottom(), colors, null,
                        android.graphics.Shader.TileMode.CLAMP));
            }
        };
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setIncludeFontPadding(false);
        return t;
    }

    private File backgroundFile() { return new File(getFilesDir(), "launcher_background"); }

    /** The player's own background picture, if one was chosen (GAME > Look), scaled down to the screen. */
    private android.graphics.Bitmap loadBackgroundPicture() { return loadPicture(backgroundFile()); }

    private android.graphics.Bitmap loadPicture(File f) {
        if (!f.isFile()) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int max = Math.max(dm.widthPixels, dm.heightPixels);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void setBackgroundPicture(Uri uri) {
        try {
            setPictureFile(uri, backgroundFile());
        } catch (Exception e) {
            toastDialog("Could not read that picture: " + e.getMessage());
            return;
        }
        if (homeBg != null) {
            homeBg.picture = loadBackgroundPicture();
            homeBg.invalidateSelf();
        }
    }

    private void showHome() {
        if (fromHome) {  // the home screen is HomeActivity: back to it
            finish();
            return;
        }
        onHome = true;
        if (homeView == null) return;
        if (homeWeb != null) {
            homeWeb.setVisibility(View.VISIBLE);
            columnView.setVisibility(View.GONE);
            if (homeMark != null) homeMark.setVisibility(View.GONE);
            if (homeBg != null) homeBg.setVisible(false, false);
            homeWeb.requestFocus();
            pushHomeInfo(null);
            return;
        }
        homeView.setVisibility(View.VISIBLE);
        pageView.setVisibility(View.GONE);
        if (homeMark != null) homeMark.setVisibility(View.VISIBLE);
        hintText.setText("\u24B6 SELECT     \u24B7 QUIT     \u2190\u2192 MOVE");
        if (homeStatus != null) homeStatus.setText(gameReady() ? "READY TO PLAY" : "CHOOSE YOUR GAME IN GAME FILES");
        View f = lastTile != null ? lastTile : firstTile;
        if (f != null) f.requestFocus();
    }

    private void showTab(String name) {
        if (!tabs.containsKey(name)) name = "PLAY";
        shownTab = name;
        onHome = false;
        if (homeWeb != null) {
            homeWeb.setVisibility(View.GONE);
            columnView.setVisibility(View.VISIBLE);
        }
        if (homeView != null) {
            View cur = getCurrentFocus();
            if (cur != null && cur.getTag() instanceof String && "tile".equals(cur.getTag())) lastTile = cur;
            homeView.setVisibility(View.GONE);
            pageView.setVisibility(View.VISIBLE);
            if (homeMark != null) homeMark.setVisibility(View.GONE);
            ((TextView) pageTitle.getChildAt(1)).setText(PAGE_TITLES.containsKey(name) ? PAGE_TITLES.get(name) : name);
            hintText.setText("\u24B6 SELECT     \u24B7 BACK     L1 / R1 PAGES");
        }
        prefs.edit().putString("launcher_tab", name).apply();
        for (java.util.Map.Entry<String, List<View>> e : tabs.entrySet())
            for (View v : e.getValue()) v.setVisibility(e.getKey().equals(name) ? View.VISIBLE : View.GONE);
        for (java.util.Map.Entry<String, Button> e : tabButtons.entrySet()) e.getValue().setSelected(e.getKey().equals(name));
        if (contentScroll != null) contentScroll.scrollTo(0, 0);
        List<View> shown = tabs.get(name);
        if (shown != null && !shown.isEmpty()) shown.get(0).requestFocus();
    }

    private void nextPage(int dir) {
        int i = java.util.Arrays.asList(PAGE_ORDER).indexOf(shownTab);
        showTab(PAGE_ORDER[(i + dir + PAGE_ORDER.length) % PAGE_ORDER.length]);
    }

    @Override
    public void onBackPressed() {
        if (!onHome && homeView != null) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (onHome && homeWeb != null && homeWeb.getVisibility() == View.VISIBLE && !startPending
                && findViewById(android.R.id.content) != null) {
            String js = null;
            switch (e.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: js = "select(sel+1)"; break;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT: js = "select(sel-1)"; break;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: js = "select(sel<4?sel+4:sel)"; break;
                case android.view.KeyEvent.KEYCODE_DPAD_UP: js = "select(sel>=4?sel-4:sel)"; break;
                case android.view.KeyEvent.KEYCODE_BUTTON_A:
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER: js = "activate(sel)"; break;
            }
            if (js != null) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) homeWeb.evaluateJavascript(js, null);
                return true;
            }
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && homeView != null && !startPending) {
            switch (e.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_BUTTON_B:
                    onBackPressed();
                    return true;
                case android.view.KeyEvent.KEYCODE_BUTTON_L1:
                    if (onHome) showTab(PAGE_ORDER[PAGE_ORDER.length - 1]); else nextPage(-1);
                    return true;
                case android.view.KeyEvent.KEYCODE_BUTTON_R1:
                    if (onHome) showTab(PAGE_ORDER[0]); else nextPage(1);
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** The section heading of the 2K menus: L1 / R1 badges and a big bold title. */
    private LinearLayout heading(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, dp(4), 0, dp(10));
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.VERTICAL);
        for (String b : new String[] {"L1", "R1"}) {
            TextView t = text(b, 9, TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadii(new float[] {dp(3), dp(3), dp(3), dp(3), dp(8), dp(8), dp(8), dp(8)});
            g.setStroke(dp(1), 0xFFE0E0E0);
            t.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(17));
            lp.bottomMargin = dp(2);
            badges.addView(t, lp);
        }
        h.addView(badges);
        TextView t = text(title, 34, TEXT);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setPadding(dp(10), 0, 0, 0);
        h.addView(t);
        return h;
    }

    /** A big home tile: drawn art (dark, or lit red when selected) and a white label box in the lower-left corner. */
    private View tile(String label, String glyph, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setTag("tile");
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(l);
        TileArt art = new TileArt(glyph);
        f.setBackground(art);
        TextView name = text(label, 15, Color.BLACK);
        name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        name.setBackgroundColor(Color.WHITE);
        name.setPadding(dp(6), dp(1), dp(6), dp(1));
        name.setLetterSpacing(0.04f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(0, 0, 0, dp(14));
        f.addView(name, lp);
        f.setOnFocusChangeListener((v, has) -> {
            art.lit = has;
            art.invalidateSelf();
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(8) : 0);
            if (has) lastTile = v;
        });
        f.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) v.requestFocus();
            return false;
        });
        f.setFocusableInTouchMode(false);
        return f;
    }

    /** Tile art: a dark grey card, or lit in WWE 2K15 red with bright streaks; a big faint symbol on the right. */
    private final class TileArt extends android.graphics.drawable.Drawable {
        final String glyph;
        boolean lit;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        TileArt(String glyph) { this.glyph = glyph; }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h,
                    lit ? new int[] {0xFFFF3A3F, 0xFFC4101A, 0xFF4A0207} : new int[] {0xFF3A3A40, 0xFF1C1C21, 0xFF0C0C0F},
                    null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            // diagonal streaks
            p.setStrokeWidth(dp(lit ? 3 : 2));
            for (int i = 0; i < 4; ++i) {
                p.setColor(lit ? (0x55FFFFFF - i * 0x10000000) : (0x22FFFFFF - i * 0x06000000));
                float x = w * (0.25f + i * 0.17f);
                c.drawLine(x, h, x + h * 0.9f, 0, p);
            }
            // a glow
            p.setShader(new android.graphics.RadialGradient(w * 0.72f, h * 0.35f, h * 0.9f,
                    lit ? 0x88FFFFFF : 0x22FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            // the big symbol
            p.setColor(lit ? 0xEEFFFFFF : 0x55FFFFFF);
            p.setTextSize(h * 0.72f);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(glyph, w * 0.74f, h * 0.68f, p);
            // the frame
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(lit ? 3 : 1));
            p.setColor(lit ? Color.WHITE : 0x55FFFFFF);
            c.drawRect(b.left + p.getStrokeWidth() / 2, b.top + p.getStrokeWidth() / 2, b.right - p.getStrokeWidth() / 2,
                    b.bottom - p.getStrokeWidth() / 2, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** The home background: blood red with dark edges and white / red brush streaks; the player's picture on the right. */
    private final class HomeBackground extends android.graphics.drawable.Drawable {
        android.graphics.Bitmap picture;
        boolean dimBottom = true;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            c.drawColor(0xFF140002);
            p.setShader(new android.graphics.RadialGradient(w * 0.62f, h * 0.42f, w * 0.62f,
                    new int[] {0xFFB3000C, 0xFF6A0006, 0x00140002}, new float[] {0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            // brush streaks: bundles of thin diagonal lines, pale on the left, bright red on the right
            java.util.Random r = new java.util.Random(15);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            float[][] bands = {{0.30f, 0x40FFFFFF}, {0.36f, 0x55FF1A24}, {0.80f, 0x66FF1A24}, {0.86f, 0x33FFFFFF}};
            for (float[] band : bands) {
                for (int i = 0; i < 26; ++i) {
                    float x = w * band[0] + r.nextFloat() * w * 0.07f;
                    float y0 = h * (0.05f + r.nextFloat() * 0.35f), y1 = h * (0.7f + r.nextFloat() * 0.35f);
                    p.setStrokeWidth(dp(1) + r.nextFloat() * dp(4));
                    int a = (int) (((((int) band[1]) >>> 24) & 0xFF) * (0.3f + r.nextFloat() * 0.7f));
                    p.setColor((a << 24) | (((int) band[1]) & 0xFFFFFF));
                    c.drawLine(x + (h - y0) * 0.45f, y0, x + (h - y1) * 0.45f, y1, p);
                }
            }
            if (picture != null) {
                // the picture fills the screen height on the right and fades into the red on its left
                float s = Math.max(h / picture.getHeight(), w * 0.62f / picture.getWidth());
                float pw = picture.getWidth() * s, ph = picture.getHeight() * s;
                android.graphics.RectF dst = new android.graphics.RectF(w - pw, (h - ph) / 2, w, (h + ph) / 2);
                int layer = c.saveLayer(0, 0, w, h, null);
                c.drawBitmap(picture, null, dst, p);
                android.graphics.Paint fade = new android.graphics.Paint();
                fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                fade.setShader(new android.graphics.LinearGradient(dst.left, 0, dst.left + pw * 0.35f, 0, 0x00000000, 0xFF000000,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(dst, fade);
                c.restoreToCount(layer);
            }
            // dark edges and a dark band under the tiles so the text stays readable
            p.setShader(new android.graphics.LinearGradient(0, 0, w * 0.45f, 0, 0xCC000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            if (dimBottom) {
                p.setShader(new android.graphics.LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xCC000000, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.5f, w, h, p);
            }
            p.setShader(null);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    private Button navButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(true);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        b.setLetterSpacing(0.04f);
        b.setPadding(dp(14), dp(6), dp(10), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(44));
        int[][] st = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {android.R.attr.state_selected}, {}};
        b.setTextColor(new android.content.res.ColorStateList(st, new int[] {Color.WHITE, Color.WHITE, ACCENT, 0xFFBDBDBD}));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, slant(ACCENT));
        d.addState(new int[] {android.R.attr.state_pressed}, slant(ACCENT));
        d.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        b.setBackground(d);
        b.setOnClickListener(l);
        b.setOnFocusChangeListener((v, f) -> { if (f) showTab(label); });
        b.setFocusable(true);
        return b;
    }

    /** A red bar with a slanted right end, like the highlight in the game's menus. */
    private android.graphics.drawable.Drawable slant(int color) {
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas c) {
                android.graphics.Rect r = getBounds();
                p.setColor(color);
                android.graphics.Path path = new android.graphics.Path();
                float k = r.height() * 0.35f;
                path.moveTo(r.left, r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right - k, r.bottom);
                path.lineTo(r.left, r.bottom);
                path.close();
                c.drawPath(path, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** Deep red with the game's big red X and a dark vignette (WWE '13 port's background, WWE 2K15 colours). */
    private final class XBackground extends android.graphics.drawable.Drawable {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            c.drawColor(BG);
            float w = r.width(), h = r.height(), cx = w * 0.62f, cy = h * 0.5f, t = h * 0.16f;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h, 0x55D0141C, 0xCCE3141C, android.graphics.Shader.TileMode.CLAMP));
            for (int k = 0; k < 2; ++k) {
                android.graphics.Path band = new android.graphics.Path();
                float dx = h * 0.75f * (k == 0 ? 1 : -1);
                band.moveTo(cx - dx - t, cy - h);
                band.lineTo(cx - dx + t, cy - h);
                band.lineTo(cx + dx + t, cy + h);
                band.lineTo(cx + dx - t, cy + h);
                band.close();
                c.drawPath(band, p);
            }
            p.setShader(new android.graphics.RadialGradient(cx, cy, Math.max(w, h) * 0.75f, 0x00000000, 0xB0000000,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            p.setShader(null);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
    private void refresh() {
        prefs.edit().putBoolean("game_ready", gameReady()).apply();  // read by the home screen (HomeActivity)
        File game = gameRoot();
        boolean base = game != null;
        boolean tu = GameFiles.titleUpdatePresent() || (game != null && game.isDirectory() && new File(game, "default.xexp").isFile());
        StringBuilder sb = new StringBuilder();
        sb.append(base ? "✔ Game: " + game.getAbsolutePath() : "✘ No game yet — choose your disc image or game folder");
        if (base && !hasAllFiles() && !game.getAbsolutePath().startsWith(root.getAbsolutePath()))
            sb.append("\n✘ Allow \"All files access\" for WWE 2K15 (choose the game again to be asked)");
        sb.append("\n");
        sb.append(tu ? "✔ Title Update 4" : "✘ Title Update 4 missing — add the title update file");
        int dlc = GameFiles.dlcCount();
        if (dlc > 0) sb.append("\nDLC packs: ").append(dlc);
        statusText.setText(sb.toString());
        playButton.setEnabled(base && tu);
        playButton.setAlpha(base && tu ? 1f : 0.4f);

        packageList.removeAllViews();
        for (GameFiles.Package p : GameFiles.packages()) {
            final GameFiles.Package pk = p;
            packageList.addView(listItem(p.kind() + ": " + p.name, "Remove", () ->
                    confirm("Remove " + pk.name + "?", () -> { GameFiles.removePackage(pk); refresh(); })));
        }

        if (dlcList != null) {
            dlcList.removeAllViews();
            int n = 0;
            for (GameFiles.Package p : GameFiles.packages()) {
                if (p.type != GameFiles.TYPE_DLC) continue;
                ++n;
                final GameFiles.Package pk = p;
                dlcList.addView(listItem(p.name, "Remove", () ->
                        confirm("Remove " + pk.name + "?", () -> { GameFiles.removePackage(pk); refresh(); })));
            }
            dlcCount.setText(n == 0 ? "No DLC added yet." : n + " DLC package" + (n == 1 ? "" : "s") + " added:");
        }

        if (logSessionText != null) {
            File log = newestLog();
            logSessionText.setText(log == null ? "No game log yet (the game has not started far enough to write one)."
                    : "Session  " + new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                            .format(new java.util.Date(log.lastModified())) + "   (" + log.getName() + ")");
        }
        backupList.removeAllViews();
        List<String[]> all = GameFiles.listBackups();
        int shown = showAllBackups ? all.size() : Math.min(3, all.size());
        for (String[] p : all.subList(0, shown)) {
            final String path = p[0];
            String label = p[1] + (p[3].equals("1") ? "  (automatic)" : "");
            backupList.addView(listItem(label, "Restore", () -> confirm("Restore this backup? Your current saves are backed up first.", () -> {
                String err = GameFiles.restoreBackup(path);
                toastDialog(err.isEmpty() ? "Backup restored." : err);
                refresh();
            })));
        }
        LinearLayout more = row();
        if (all.size() > 3) {
            more.addView(button(showAllBackups ? "Show fewer" : "Show all (" + all.size() + ")", v -> {
                showAllBackups = !showAllBackups;
                refresh();
            }));
        }
        if (all.size() > 20) {
            more.addView(button("Delete old backups (keep the newest 20)", v -> confirm(
                    "Delete " + (all.size() - 20) + " old save backups? The newest 20 are kept. This can't be undone.", () -> {
                        int n = GameFiles.deleteOldBackups(20);
                        toastDialog(n + " old backups deleted.");
                        refresh();
                    })));
        }
        if (more.getChildCount() > 0) backupList.addView(more, wrap(dp(4)));
    }
    private boolean showAllBackups;

    private boolean gameReady() {
        File g = gameRoot();
        return g != null && (GameFiles.titleUpdatePresent() || (g.isDirectory() && new File(g, "default.xexp").isFile()));
    }

    // ------------------------------------------------------------------------------------------ the game's location

    /** The game to run: the chosen disc image / folder (read in place), else a copy installed into the app. */
    private File gameRoot() {
        String p = prefs.getString("game_path", "");
        if (!p.isEmpty()) {
            File f = new File(p);
            if (f.isFile() && p.toLowerCase(Locale.ROOT).endsWith(".iso")) return f;
            if (new File(f, "default.xex").isFile()) return f;
            if (!hasAllFiles() && (f.exists() || p.startsWith("/storage/"))) return f;  // can't look yet: trust the choice
        }
        return GameFiles.gameInstalled() ? GameFiles.gameDir() : null;
    }

    private boolean hasAllFiles() {
        return android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null) refresh();  // back from the "All files access" settings, for example
    }

    private void chooseGame(boolean folder) {
        if (android.os.Build.VERSION.SDK_INT < 30
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
        if (!hasAllFiles()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("All files access")
                    .setMessage("To play the game where it is (no 8 GB copy), WWE 2K15 needs to read it. On the next screen, "
                            + "turn on \"Allow access to manage all files\", come back, and choose the game again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (folder) {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER);
        } else {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_ISO);
        }
    }

    /** A picker's answer as a real path ("primary:Download/x.iso" -> /storage/emulated/0/Download/x.iso), or null. */
    private static String uriToPath(Uri uri, boolean tree) {
        try {
            String id = tree ? android.provider.DocumentsContract.getTreeDocumentId(uri)
                    : android.provider.DocumentsContract.getDocumentId(uri);
            if (id.startsWith("raw:")) return id.substring(4);
            int c = id.indexOf(':');
            if (c < 0) return null;
            String vol = id.substring(0, c), rest = id.substring(c + 1);
            String base = vol.equalsIgnoreCase("primary") ? "/storage/emulated/0" : "/storage/" + vol;
            return rest.isEmpty() ? base : base + "/" + rest;
        } catch (Exception e) {
            return null;
        }
    }

    private void useGamePath(Uri uri, boolean folder) {
        String p = uriToPath(uri, folder);
        if (p == null) {
            toastDialog("Couldn't use that location. Pick it from the phone's storage (Internal storage > ...), "
                    + "not from \"Recent\" or \"Downloads\".");
            return;
        }
        File f = new File(p);
        if (folder && !new File(f, "default.xex").isFile()) {
            // a folder one level up (e.g. 2K15 holding 545408B6/default.xex)
            File[] subs = f.listFiles(File::isDirectory);
            if (subs != null) for (File d : subs) if (new File(d, "default.xex").isFile()) { f = d; break; }
        }
        if (folder && !new File(f, "default.xex").isFile()) {
            toastDialog("No default.xex in " + f.getAbsolutePath() + ". Choose the folder that holds the game's default.xex.");
            return;
        }
        if (!folder && !(f.isFile() && p.toLowerCase(Locale.ROOT).endsWith(".iso"))) {
            toastDialog("That is not an .iso file the app can read: " + p);
            return;
        }
        prefs.edit().putString("game_path", f.getAbsolutePath()).apply();
        // A title update package next to the game (its folder or the one above, e.g. 2K15/tu00000004_00000000):
        // added by itself.
        if (!GameFiles.titleUpdatePresent() && !(folder && new File(f, "default.xexp").isFile())) {
            File parent = f.getParentFile();
            for (File dir : new File[] {f.isDirectory() ? f : parent, f.isDirectory() ? parent : null}) {
                File[] files = dir == null ? null : dir.listFiles(File::isFile);
                if (files == null) continue;
                for (File c : files) {
                    GameFiles.Package pk = GameFiles.inspect(c);
                    if (pk == null || pk.type != GameFiles.TYPE_TITLE_UPDATE || pk.titleId != GameFiles.TITLE_ID) continue;
                    try {
                        File copy = new File(getCacheDir(), c.getName());
                        try (InputStream in = new java.io.FileInputStream(c); OutputStream out = new FileOutputStream(copy)) {
                            GameFiles.copyStream(in, out);
                        }
                        if (GameFiles.importPackage(copy).isEmpty()) break;
                    } catch (Exception ignored) {
                    }
                }
                if (GameFiles.titleUpdatePresent()) break;
            }
        }
        String note = GameFiles.titleUpdatePresent() ? "\nTitle Update 4 found and added." : "";
        if (folder && new File(f, "default.xexp").isFile()) note = "\nTitle update (default.xexp) found next to it.";
        else if (note.isEmpty()) note = "\nNow add Title Update 4 with \"Add title update or DLC…\".";
        toastDialog("Game: " + f.getAbsolutePath() + note);
        refresh();
    }

    // ------------------------------------------------------------------------------------------ launch

    /** The settings -> the game's command-line arguments (port/src: wwe2k15_*, and the SDK's own settings). */
    private void startGame() {
        try {
            List<String> a = new ArrayList<>();
            File game = gameRoot();
            if (game == null) { toastDialog("Choose your WWE 2K15 disc image or game folder first."); return; }
            a.add("--game_data_root=" + game.getAbsolutePath());
            // Title update and DLC packages: the game unpacks / installs them itself (dlc_install.h).
            a.add("--wwe2k15_dlc_dir=" + GameFiles.dlcDir().getAbsolutePath());
            a.add("--wwe2k15_update_dir=" + GameFiles.updateDir().getAbsolutePath());
            boolean tuPackage = false;
            for (GameFiles.Package p : GameFiles.packages()) if (p.type == GameFiles.TYPE_TITLE_UPDATE) tuPackage = true;
            if (tuPackage || new File(GameFiles.updateDir(), "default.xexp").isFile())
                a.add("--update_data_root=" + GameFiles.updateDir().getAbsolutePath());

            int res = Math.max(0, Math.min(RES_HEIGHTS.length - 1, prefs.getInt("resolution", 3)));
            int h = RES_HEIGHTS[res];
            int scale = h > 720 ? 2 : 1;
            a.add("--resolution_scale=" + scale);
            a.add("--gpu_native_truth_draw_height=" + (h >= 720 * scale ? 0 : h));
            int fps = Math.max(0, Math.min(3, prefs.getInt("frame_rate2", 0)));
            a.add("--wwe2k15_60fps=" + (fps == 0 || fps == 1));          // entrances at 60
            a.add("--wwe2k15_step30=" + (fps == 0 ? 2 : fps == 2 ? 1 : 0));  // real-speed 30: auto / always / off
            a.add("--wwe2k15_fps_limit=0");
            a.add("--wwe2k15_renderer=" + (prefs.getInt("renderer", 0) == 0 ? 1 : 0));
            if (prefs.getBoolean("upscaler", false)) a.add("--present_effect=sgsr");
            a.add("--present_frame_generation=" + prefs.getBoolean("frame_gen", false));
            a.add("--present_letterbox=" + !prefs.getBoolean("stretch", false));
            a.add("--wwe2k15_show_fps=" + prefs.getBoolean("show_fps", false));
            a.add("--wwe2k15_big_cores=" + prefs.getBoolean("big_cores", true));
            a.add("--wwe2k15_universe_free_shows=" + prefs.getBoolean("universe_free", false));
            a.add("--wwe2k15_pc_texts=false");  // keep the game's own "Xbox 360" wording (the PC port says "PC")
            a.add("--vulkan_log_debug_messages=false");  // Adreno's driver warnings cost speed
            float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
            try {
                for (android.view.Display.Mode m : getWindowManager().getDefaultDisplay().getSupportedModes())
                    hz = Math.max(hz, m.getRefreshRate());
            } catch (Throwable ignored) {
            }
            a.add("--wwe2k15_display_hz=" + Math.round(hz));
            String driver = currentDriver();
            if (!driver.equals("system")) {
                File dd = new File(getFilesDir(), "drivers/" + driver);
                String lib = driverLibrary(dd);
                if (lib != null) {
                    File trial = new File(getFilesDir(), "driver_trial");
                    trial.createNewFile();
                    a.add("--wwe2k15_android_driver=" + lib);
                    a.add("--wwe2k15_android_driver_dir=" + dd.getAbsolutePath() + "/");
                    a.add("--wwe2k15_android_lib_dir=" + getApplicationInfo().nativeLibraryDir + "/");
                    a.add("--wwe2k15_android_tmp_dir=" + getCacheDir().getAbsolutePath() + "/");
                    a.add("--wwe2k15_android_driver_trial=" + trial.getAbsolutePath());
                }
            }
            for (String line : prefs.getString("extra_args", "").split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) a.add(line);
            }
            write(new File(root, "launch_options.txt"), android.text.TextUtils.join("\n", a) + "\n");
            if (prefs.getBoolean("auto_backup", true)) GameFiles.backupSaves(true);
        } catch (Exception e) {
            toastDialog("Could not save the settings: " + e.getMessage());
            return;
        }
        startActivity(new Intent(this, WWE2K15Activity.class));
        finish();
    }

    // ------------------------------------------------------------------------------------------ GPU drivers

    /** The phone's own driver first, then the bundled and imported driver packages (files/drivers/<name>/). */
    private List<String> driverIds() {
        List<String> ids = new ArrayList<>();
        ids.add("system");
        File[] dirs = new File(getFilesDir(), "drivers").listFiles(File::isDirectory);
        if (dirs != null) {
            List<String> extra = new ArrayList<>();
            for (File d : dirs) if (driverLibrary(d) != null) extra.add(d.getName());
            java.util.Collections.sort(extra, String.CASE_INSENSITIVE_ORDER);
            ids.addAll(extra);
        }
        return ids;
    }

    /** The driver library's file name: meta.json's "libraryName", or the folder's only .so. */
    private static String driverLibrary(File dir) {
        File meta = new File(dir, "meta.json");
        if (meta.isFile()) {
            try (InputStream in = new java.io.FileInputStream(meta)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                GameFiles.copyStream(in, bo);
                String lib = new org.json.JSONObject(bo.toString("UTF-8")).optString("libraryName", "");
                if (!lib.isEmpty() && new File(dir, lib).isFile()) return lib;
            } catch (Exception ignored) {
            }
        }
        String[] so = dir.list((d, n) -> n.endsWith(".so"));
        return so != null && so.length > 0 ? so[0] : null;
    }

    private String driverLabel(String id) {
        if (id.equals("system")) return "Phone's own driver (safest)";
        return id.replace('_', ' ') + (id.toLowerCase(Locale.ROOT).contains("turnip") ? " (Turnip, often faster)" : "");
    }

    private String currentDriver() {
        String id = prefs.getString("driver_name", "system");
        // A driver whose last start never got far enough (the game deletes the trial file once it draws) is not
        // offered again silently: fall back to the phone's own driver and say so once.
        File trial = new File(getFilesDir(), "driver_trial");
        if (!id.equals("system") && trial.isFile() && !prefs.getBoolean("trial_warned", false) && playButton != null) {
            prefs.edit().putBoolean("trial_warned", true).apply();
            toastDialog("The game did not get far with the driver \"" + id.replace('_', ' ')
                    + "\" last time. If it crashes again, pick \"Phone's own driver\" in GRAPHICS.");
        }
        if (!trial.isFile()) prefs.edit().putBoolean("trial_warned", false).apply();
        if (!id.equals("system") && driverLibrary(new File(getFilesDir(), "drivers/" + id)) == null) id = "system";
        return id;
    }

    /** Installs an adrenotools driver package (.zip with meta.json and the driver library). */
    private void installDriver(Uri uri) {
        runLong("Installing the GPU driver", () -> {
            String file = displayName(uri);
            String base = file.replaceAll("(?i)\\.zip$", "").replaceAll("[^A-Za-z0-9._-]", "_");
            if (base.isEmpty() || base.equals("system")) base = "driver";
            File dest = new File(getFilesDir(), "drivers/" + base);
            GameFiles.deleteTree(dest);
            dest.mkdirs();
            boolean hasLibrary;
            try (ZipInputStream zin = new ZipInputStream(getContentResolver().openInputStream(uri))) {
                hasLibrary = unzipFlat(zin, dest);
            }
            if (!hasLibrary) {
                GameFiles.deleteTree(dest);
                return "This zip doesn't contain a GPU driver (no .so library found).";
            }
            prefs.edit().putString("driver_name", base).apply();
            handler.post(this::buildUi);
            return "Driver installed and selected: " + base.replace('_', ' ')
                    + "\nIf the game doesn't start or looks wrong, switch back to the phone's own driver.";
        });
    }

    private static void write(File f, String s) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }

    // ------------------------------------------------------------------------------------------ file picking

    private void pick(int request, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); ++i) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        switch (request) {
            case REQ_ISO: installIso(uris.get(0)); break;
            case REQ_PACKAGE: installPackages(uris); break;
            case REQ_DRIVER: installDriver(uris.get(0)); break;
            case REQ_PICK_ISO: useGamePath(uris.get(0), false); break;
            case REQ_PICK_FOLDER: useGamePath(uris.get(0), true); break;
            case REQ_BACKGROUND: setBackgroundPicture(uris.get(0)); break;
            case REQ_TILE_IMAGE: setTileImage(uris.get(0)); break;
            case REQ_PICTURE_ZIP: importPictureZip(uris.get(0)); break;
            case REQ_SPLASH:
                try {
                    setPictureFile(uris.get(0), splashFile());
                    showSplash();
                } catch (Exception e) {
                    toastDialog("Could not read that picture: " + e.getMessage());
                }
                break;
        }
    }

    private void installIso(Uri uri) {
        runLong("Installing the game from your disc image", () -> {
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                String err = GameFiles.extractIso("/proc/self/fd/" + pfd.getFd());
                if (err.isEmpty()) prefs.edit().remove("game_path").apply();
                return err.isEmpty() ? "WWE 2K15 is installed." + (GameFiles.titleUpdatePresent() ? ""
                        : "\nNow add Title Update 4 with \"Add title update or DLC…\".") : err;
            }
        });
    }

    private void installPackages(List<Uri> uris) {
        runLong("Adding the title update / DLC", () -> {
            StringBuilder messages = new StringBuilder();
            for (Uri uri : uris) {
                File copy = copyToCache(uri, "packages");
                GameFiles.Package p = GameFiles.inspect(copy);
                String err = GameFiles.importPackage(copy);
                copy.delete();
                messages.append(p != null ? p.kind() + " " + p.name : displayName(uri)).append(": ")
                        .append(err.isEmpty() ? "added (installed when the game starts)" : err).append('\n');
            }
            return messages.toString().trim();
        });
    }

    private File copyToCache(Uri uri, String sub) throws Exception {
        File dir = new File(getCacheDir(), sub);
        dir.mkdirs();
        File dest = new File(dir, displayName(uri));
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dest)) {
            GameFiles.copyStream(in, out);
        }
        return dest;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n.replace('/', '_');
            }
        } catch (Exception ignored) {
        }
        return "file_" + System.currentTimeMillis();
    }

    // ------------------------------------------------------------------------------------------ logs

    private File newestLog() {
        File[] logs = new File(root, "logs").listFiles(f -> f.isFile() && (f.getName().endsWith(".log") || f.getName().endsWith(".txt")));
        if (logs == null || logs.length == 0) return null;
        File newest = logs[0];
        for (File f : logs) if (f.lastModified() > newest.lastModified()) newest = f;
        return newest;
    }

    private static String tail(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length(), start = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - start)];
            r.seek(start);
            r.readFully(b);
            String t = new String(b, "UTF-8");
            return start > 0 ? "...(start cut)...\n" + t.substring(Math.max(0, t.indexOf('\n') + 1)) : t;
        } catch (Exception e) {
            return "";
        }
    }

    /** Android's log for this app (its own entries; the last maxLines lines). */
    private static String logcat(int maxLines) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "threadtime", "-t", Integer.toString(maxLines)});
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) { GameFiles.copyStream(in, bo); }
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "(Android's log could not be read: " + e.getMessage() + ")";
        }
    }

    private String phoneInfo() {
        return "device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\nandroid: "
                + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\nsoc: "
                + (android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "?") + "\napp: "
                + Updater.currentVersion(this) + "\ngame: " + prefs.getString("game_path", "(copy in app)")
                + "\nall files access: " + hasAllFiles() + "\n";
    }

    /** The logs as one text: phone, start settings, the game's log (end) and Android's log (end). */
    private String logText(int gameBytes, int logcatLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== WWE 2K15 Android logs ===\n").append(phoneInfo());
        String opts = tail(new File(root, "launch_options.txt"), 8192);
        if (!opts.isEmpty()) sb.append("\n=== launch options ===\n").append(opts);
        File log = newestLog();
        sb.append("\n=== game log: ").append(log == null ? "none" : log.getName()).append(" ===\n").append(tail(log, gameBytes));
        sb.append("\n=== Android log (app) ===\n").append(logcat(logcatLines));
        return sb.toString();
    }

    private void viewLog() {
        String t = logText(256 * 1024, 2000);
        TextView tv = text(t, 11, TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                .setTitle(newestLog() == null ? "Logs (Android's log only)" : "Log: " + newestLog().getName())
                .setView(sv).setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> copyLogs()).show();
    }

    private void copyLogs() {
        // The clipboard takes a few hundred KB at most: the ends of the logs, where the trouble is.
        String t = logText(150 * 1024, 1500);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WWE 2K15 logs", t));
        toastDialog("Logs copied (" + (t.length() / 1024) + " KB). Paste them in the chat.");
    }

    /** Writes the logs zip (game logs, Android's log, start settings, phone) into Downloads and returns its address. */
    private Uri writeLogsZip() throws Exception {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "WWE2K15-logs-" + stamp + ".zip");
        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
            File[] logs = new File(root, "logs").listFiles(File::isFile);
            if (logs != null) {
                java.util.Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int n = 0; n < Math.min(logs.length, 5); ++n) addZip(z, logs[n], "logs/" + logs[n].getName());
            }
            addZip(z, new File(root, "launch_options.txt"), "launch_options.txt");
            z.putNextEntry(new java.util.zip.ZipEntry("logcat.txt"));
            z.write(logcat(20000).getBytes("UTF-8"));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("phone.txt"));
            z.write(phoneInfo().getBytes("UTF-8"));
            z.closeEntry();
        }
        return uri;
    }

    private void saveLogsToDownloads() {
        try {
            writeLogsZip();
            toastDialog("Saved to Downloads (WWE2K15-logs-....zip). Send that file.");
        } catch (Exception e) {
            toastDialog("Couldn't save the logs: " + e.getMessage());
        }
    }

    private void shareLogs() {
        try {
            Uri uri = writeLogsZip();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "WWE 2K15 Android logs");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share the logs"));
        } catch (Exception e) {
            toastDialog("Couldn't share the logs: " + e.getMessage());
        }
    }

    private static int addZip(java.util.zip.ZipOutputStream z, File f, String name) throws java.io.IOException {
        if (!f.isFile()) return 0;
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        try (InputStream in = new java.io.FileInputStream(f)) { GameFiles.copyStream(in, z); }
        z.closeEntry();
        return 1;
    }

    // ------------------------------------------------------------------------------------------ long operations

    interface Job {
        String run() throws Exception;
    }

    private void runLong(String title, Job job) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView label = text("Starting…", 14, TEXT);
        box.addView(bar);
        box.addView(label);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> GameFiles.cancel()).create();
        dialog.show();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                String[] p = GameFiles.progress().split("\\|", 3);
                try {
                    long done = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
                    if (total > 0) bar.setProgress((int) (done * 1000 / total));
                    bar.setIndeterminate(total <= 0);
                } catch (Exception ignored) {
                }
                if (p.length > 2 && !p[2].isEmpty()) label.setText(p[2]);
                if (dialog.isShowing()) handler.postDelayed(this, 200);
            }
        };
        handler.post(poll);
        new Thread(() -> {
            String message;
            try {
                message = job.run();
            } catch (Exception e) {
                message = "Something went wrong: " + e.getMessage();
            }
            final String m = message;
            handler.post(() -> {
                dialog.dismiss();
                toastDialog(m == null || m.isEmpty() ? "Done." : m);
                refresh();
            });
        }).start();
    }

    // ------------------------------------------------------------------------------------------ small widgets

    interface IntListener {
        void on(int value);
    }

    interface BoolListener {
        void on(boolean value);
    }    private LinearLayout section(LinearLayout page, String title) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        // The header: a white bar with black italic capitals and a red line under it, as in the game's pause menu.
        TextView t = text(title.toUpperCase(Locale.ROOT), 17, 0xFF1A0306);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        t.setPadding(dp(16), dp(5), dp(16), dp(5));
        t.setBackground(slant(0xFFF2E6E6));
        outer.addView(t, matchWidth(0));
        View red = new View(this);
        red.setBackgroundColor(ACCENT);
        outer.addView(red, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(10), dp(18), dp(14));
        card.setBackgroundColor(CARD);
        outer.addView(card, matchWidth(0));
        page.addView(outer, matchWidth(dp(14)));
        if (page != updateBox) tag(outer);
        return card;
    }

    private LinearLayout updateBox;

    private void showUpdate(Updater.Release r) {
        if (updateBox == null) return;
        updateBox.removeAllViews();
        LinearLayout box = section(updateBox, "Update available: " + r.tag);
        String notes = r.notes.length() > 600 ? r.notes.substring(0, 600) + "…" : r.notes;
        box.addView(text(notes.replace("**", "").replace("## ", ""), 13, DIM));
        box.addView(button("Download and install " + r.tag + " (" + (r.apkSize >> 20) + " MB)",
                v -> Updater.downloadAndInstall(this, r)), wrap(dp(6)));
        box.addView(text("Your game files, saves and settings stay.", 13, DIM));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Updater.handleStatus(this, intent);
        String page = intent.getStringExtra(EXTRA_OPEN_PAGE);
        boolean startNow = intent.getBooleanExtra(EXTRA_START_GAME, false);
        if ((page != null || startNow) && homeView != null) {
            setIntent(intent);
            fromHome = true;
            openFromHome(page, startNow);
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(ACCENT));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(ACCENT));
        states.addState(new int[]{}, rounded(BUTTON));
        b.setBackground(states);
        b.setOnClickListener(l);
        b.setFocusable(true);
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(2));
        return d;
    }

    private CheckBox checkbox(String label, boolean checked, BoolListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        c.setChecked(checked);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        c.setOnCheckedChangeListener((v, isChecked) -> l.on(isChecked));
        return c;
    }

    private LinearLayout spinnerRow(String label, String[] options, int selected, IntListener l) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label, 15, TEXT);
        r.addView(t, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        Spinner s = new Spinner(this, Spinner.MODE_DIALOG);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(TEXT);
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(selected, options.length - 1)));
        s.setBackground(rounded(BUTTON));
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                l.on(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        r.addView(s, new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private View listItem(String label, String action, Runnable onAction) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label, 14, TEXT);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = button(action, v -> onAction.run());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        r.addView(b);
        return r;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        GradientDrawable gap = new GradientDrawable();
        gap.setSize(dp(10), 1);
        gap.setColor(Color.TRANSPARENT);
        r.setDividerDrawable(gap);
        r.setPadding(0, dp(6), 0, dp(6));
        return r;
    }

    private LinearLayout.LayoutParams matchWidth(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private void toastDialog(String message) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message).setPositiveButton("OK", null).show();
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message).setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("No", null).show();
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l);
        return out;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }}
