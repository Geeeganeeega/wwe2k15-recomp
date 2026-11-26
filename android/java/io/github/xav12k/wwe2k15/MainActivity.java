// WWE 2K15 recompiled, Android: the start screen. It does only what the first test builds need:
//   1. get "All files access" (the game's files are read from a normal folder, e.g. Download/WWE 2K15),
//   2. check that the folder holds default.xex,
//   3. start the game (GameActivity) with --game_data_root=<folder> and any extra arguments typed here,
//   4. copy the game's logs to Download/WWE2K15_logs so they can be looked at or sent.
// Built in code (no layout files) to keep the packaging steps small.
package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.database.Cursor;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "wwe2k15";
    private static final String DEFAULT_FOLDER = "/sdcard/Download/WWE 2K15";

    private EditText folderEdit;
    private EditText argsEdit;
    private TextView status;
    private TextView dlcStatus;
    private int touchMode, touchSize, touchOpacity, fpsMask;
    private boolean touchVibrate;
    private static final int REQ_DLC = 7102, REQ_ISO = 7101, REQ_FOLDER = 7104;

    // ---- Look: WWE 2K15 red. Deep crimson panels and buttons over a red-to-black background, bright red accents,
    // white / silver text (the launcher layout itself is the WWE 2K17 port's).
    private static final int RED = 0xFFE3141C, RED_DARK = 0xFF6E0B10, SILVER = 0xFFE2C9CB, WHITE = 0xFFFFF5F5;
    private static final int PANEL = 0xCC2A070B, BUTTON = 0xF03A0A10, BUTTON_DOWN = 0xFF5C0E16, FIELD = 0xFF30090D;

    private FrameLayout sectionHost;
    private ScrollView sectionScroll;
    private final List<Button> navButtons = new ArrayList<>();
    private final List<View> sections = new ArrayList<>();
    private TextView sectionTitle;
    private int currentSection = 0;

    private static final int ACCENT = 0xFFFF2A2A, CARD = 0xFF3A0B10;
    private LinearLayout leftButtons, rowsBox;
    private android.widget.ProgressBar isoBar;
    private TextView isoText;
    private SharedPreferences gp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Back from the game (its menu button): this process may have been recycled while the game ran, so the intro
        // flag is gone; the game launch left a note and the intro is not shown again.
        if (!IntroActivity.done && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("from_game", false)) {
            IntroActivity.done = true;
        }
        if (!IntroActivity.done) {  // the intro always comes first
            startActivity(new Intent(this, IntroActivity.class));
            finish();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        gp = prefs;
        if (prefs.getInt("defaults_v", 0) < 2) {  // new lowest-cost defaults, once
            prefs.edit().remove("gpu_profile").remove("msaa").remove("fps_limit").remove("res_scale").remove("upscaler")
                    .remove("tex_quality").remove("aniso").remove("big_cores").remove("vsync").putInt("defaults_v", 2).apply();
        }
        if (!prefs.contains("gpu_prof2")) {  // Safe profile removed: 0 = Balanced, 1 = Fast (old saves: Safe -> Balanced)
            prefs.edit().putInt("gpu_prof2", prefs.getInt("gpu_profile", 1) >= 2 ? 1 : 0).apply();
        }
        if (prefs.getInt("defaults_v", 0) < 3) {  // the game speed fix is now on by default, once
            prefs.edit().putInt("dt_patch", 1).putInt("defaults_v", 3).apply();
        }
        touchMode = prefs.getInt("touch_mode", 0);
        touchSize = prefs.getInt("touch_size", 100);
        touchOpacity = prefs.getInt("touch_opacity", 70);
        touchVibrate = prefs.getBoolean("touch_vibrate", true);
        fpsMask = prefs.getInt("fps_mask", 0);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wlp);

        status = new TextView(this);
        folderEdit = field("Game folder or .iso path");
        folderEdit.setText(prefs.getString("folder", DEFAULT_FOLDER));
        argsEdit = field("--log_level=info");
        String savedArgs = prefs.getString("args", "");
        if (savedArgs.trim().equals("--log_level=debug")) savedArgs = "--log_level=info";
        argsEdit.setText(savedArgs);
        dlcStatus = new TextView(this);

        final FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF1A0306, 0xFF5E0910, 0xFFA30F18}));
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        final int gap = dp(12);

        // ---- Left: the game's name, its files, and the buttons (which depend on whether a game is imported).
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -1, 0.45f);
        leftLp.setMargins(gap, gap, gap / 2, gap);
        content.addView(left, leftLp);

        TextView name = new TextView(this);
        android.text.SpannableString t = new android.text.SpannableString("WWE 2K15");
        t.setSpan(new android.text.style.ForegroundColorSpan(WHITE), 0, 4, 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 4, 8, 0);
        name.setText(t);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        name.setLetterSpacing(0.04f);
        left.addView(name);
        TextView sub = new TextView(this);
        sub.setText("Xbox 360 \u00B7 natively recompiled for Android");
        sub.setTextColor(SILVER);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        left.addView(sub);

        LinearLayout filesCard = new LinearLayout(this);
        filesCard.setOrientation(LinearLayout.VERTICAL);
        filesCard.setBackground(rounded(0x66000000, dp(18), 0x55E8432A));
        filesCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView filesTitle = new TextView(this);
        filesTitle.setText("GAME FILES");
        filesTitle.setTextColor(ACCENT);
        filesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        filesTitle.setLetterSpacing(0.1f);
        filesTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        filesCard.addView(filesTitle);
        status.setTextColor(WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(6), 0, 0);
        filesCard.addView(status);
        LinearLayout.LayoutParams fcLp = new LinearLayout.LayoutParams(-1, -2);
        fcLp.topMargin = gap;
        left.addView(filesCard, fcLp);

        leftButtons = new LinearLayout(this);
        leftButtons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-1, -2);
        lbLp.topMargin = gap;
        left.addView(leftButtons, lbLp);

        // ---- Right: every option as a row (label, value in the accent colour); a tap opens Android's own select menu.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(0x55000000, dp(22), 0x66E8432A));
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -1, 0.55f);
        rightLp.setMargins(gap / 2, gap, gap, gap);
        content.addView(panel, rightLp);
        TextView ot = new TextView(this);
        ot.setText("OPTIONS");
        ot.setTextColor(ACCENT);
        ot.setTypeface(Typeface.DEFAULT_BOLD);
        ot.setLetterSpacing(0.1f);
        ot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(ot);
        TextView os = new TextView(this);
        os.setText("Applied when you press PLAY.");
        os.setTextColor(SILVER);
        os.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        os.setPadding(0, dp(2), 0, dp(8));
        panel.addView(os);
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(rowsBox);
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l = insets.getSystemWindowInsetLeft(), tp = insets.getSystemWindowInsetTop();
            int r = insets.getSystemWindowInsetRight(), bt = insets.getSystemWindowInsetBottom();
            android.view.DisplayCutout dc = insets.getDisplayCutout();
            if (dc != null) {
                l = Math.max(l, dc.getSafeInsetLeft());
                tp = Math.max(tp, dc.getSafeInsetTop());
                r = Math.max(r, dc.getSafeInsetRight());
                bt = Math.max(bt, dc.getSafeInsetBottom());
            }
            content.setPadding(l, tp, r, bt);
            return insets;
        });
        setContentView(root);
        hideSystemBars(root);
        checkDriverTrial();
        checkDlcTrial();
        buildRows();
        refresh();
        installBundledDrivers();
    }

    // The driver packages shipped in the APK (assets/drivers/*.zip) are imported once, so they show in the driver list.
    private void installBundledDrivers() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        new Thread(() -> {
            boolean any = false;
            try {
                String[] names = getAssets().list("drivers");
                if (names == null) return;
                for (String n : names) {
                    String key = "bundled_driver_" + n;
                    if (p.getBoolean(key, false) || !n.endsWith(".zip")) continue;
                    try (InputStream in = getAssets().open("drivers/" + n)) {
                        DriverImport.Result r = DriverImport.run(in, n, driversDir());
                        if (r.ok) {
                            p.edit().putBoolean(key, true).apply();
                            any = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (any) runOnUiThread(this::buildRows);
        }, "bundled-drivers").start();
    }

    // ---- Option rows

    private interface Act { void run(); }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private void addRow(String label, String value, Act act) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(rounded(CARD, dp(14), 0));
        r.setPadding(dp(16), dp(12), dp(14), dp(12));
        TextView a = new TextView(this);
        a.setText(label);
        a.setTextColor(WHITE);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText(value + "  \u203A");
        v.setTextColor(ACCENT);
        v.setGravity(Gravity.END);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(x -> act.run());
        rowsBox.addView(r, rowLp());
    }

    // A stored choice: Android's own single-choice dialog.
    private void addChoice(String label, String key, int def, String... names) {
        int cur = Math.max(0, Math.min(names.length - 1, gp.getInt(key, def)));
        addRow(label, names[cur], () -> new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    gp.edit().putInt(key, which).apply();
                    d.dismiss();
                    buildRows();
                }).show());
    }

    private void buildRows() {
        rowsBox.removeAllViews();
        addChoice("Resolution", "res_h", 3, "360p (fastest)", "480p", "540p", "720p (default)", "1080p (heavy)", "1440p (very heavy)");
        addChoice("Performance mode", "perf_mode", 0, "Off", "On: 540p at most + sharp upscaling");
        addChoice("FPS limit", "fps_limit", 0, "Game's pace (max 60)", "30 FPS", "60 FPS");
        addChoice("V-Sync", "vsync", 0, "Off (smoother)", "On");
        addChoice("Screen shape", "screen_shape", 0, "16:9 - original (black bars on wide phones)", "18:9", "19.5:9", "20:9", "21:9", "Fill the whole screen (stretched)");
        addChoice("Frame generation", "framegen", 0, "Off", "On (30 -> 60 FPS look, +latency)");
        addChoice("GPU profile", "gpu_prof2", 0, "Balanced (recommended)", "Fast (more shortcuts)");
        addChoice("Texture quality", "tex_quality", 2, "High", "Medium", "Low (fastest)");
        addChoice("Texture filtering", "aniso", 1, "Game's own", "2x", "4x", "8x", "16x");
        addChoice("Upscaler", "upscaler", 0, "Off (bilinear)", "SGSR sharpening");
        addChoice("Entrances at 60 FPS", "scenes60", 1, "Off (console's 30)", "On (drops back to 30 if too slow)");
        addChoice("CPU: fast cores", "big_cores", 1, "Off", "On");
        addRow("Frame monitor", fpsMask == 0 ? "Off" : "On", () -> {
            String[] n = {"FPS", "Frame time (ms)", "Battery temperature"};
            int[] bits = {FpsOverlay.SHOW_FPS, FpsOverlay.SHOW_FRAME_TIME, FpsOverlay.SHOW_TEMP};
            boolean[] on = new boolean[3];
            for (int k = 0; k < 3; k++) on[k] = (fpsMask & bits[k]) != 0;
            new AlertDialog.Builder(this).setTitle("Frame monitor").setMultiChoiceItems(n, on, (d, w, c) -> on[w] = c)
                    .setPositiveButton("OK", (d, w) -> {
                        fpsMask = 0;
                        for (int k = 0; k < 3; k++) if (on[k]) fpsMask |= bits[k];
                        gp.edit().putInt("fps_mask", fpsMask).apply();
                        buildRows();
                    }).setNegativeButton("Cancel", null).show();
        });
        String[] tm = {"Automatic (hidden with a controller)", "Always show", "Never show"};
        addRow("Touch controls", new String[] {"Automatic", "Always", "Never"}[touchMode], () ->
                new AlertDialog.Builder(this).setTitle("Touch controls").setSingleChoiceItems(tm, touchMode, (d, w) -> {
                    touchMode = w;
                    gp.edit().putInt("touch_mode", w).apply();
                    d.dismiss();
                    buildRows();
                }).show());
        int[] sizes = {60, 70, 80, 90, 100, 110, 120, 130, 140, 150};
        addRow("Touch size", touchSize + "%", () -> pickNumber("Touch size", sizes, touchSize, v -> {
            touchSize = v;
            gp.edit().putInt("touch_size", v).apply();
        }));
        int[] ops = {20, 30, 40, 50, 60, 70, 80, 90, 100};
        addRow("Touch opacity", touchOpacity + "%", () -> pickNumber("Touch opacity", ops, touchOpacity, v -> {
            touchOpacity = v;
            gp.edit().putInt("touch_opacity", v).apply();
        }));
        addRow("Touch vibration", touchVibrate ? "On" : "Off", () ->
                new AlertDialog.Builder(this).setTitle("Touch vibration").setSingleChoiceItems(new String[] {"On", "Off"}, touchVibrate ? 0 : 1, (d, w) -> {
                    touchVibrate = w == 0;
                    gp.edit().putBoolean("touch_vibrate", touchVibrate).apply();
                    d.dismiss();
                    buildRows();
                }).show());
        addRow("Edit touch layout", "Move / resize", () -> {
            Intent e = new Intent(this, TouchEditActivity.class);
            e.putExtra(TouchEditActivity.EXTRA_LOOK, new int[] {touchSize, touchOpacity});
            startActivity(e);
        });
        addRow("Vulkan driver", driverLabel(), this::driverDialog);
        addRow("DLC and title update", dlcStatus.getText().toString(), this::showDlcManager);
        addRow("Extra arguments", argsEdit.getText().toString().trim().isEmpty() ? "None" : "Set", () -> {
            EditText e = field("--log_level=info");
            e.setText(argsEdit.getText());
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            new AlertDialog.Builder(this).setTitle("Extra arguments (one per line)").setView(e)
                    .setPositiveButton("OK", (d, w) -> { argsEdit.setText(e.getText()); buildRows(); })
                    .setNegativeButton("Cancel", null).show();
        });
        addRow("Game file / folder", "Change", this::showGameChooser);
        addRow("Copy logs", "Download folder", this::copyLogs);
        addRow("Credits", "Links", () -> startActivity(new Intent(this, IntroActivity.class).putExtra(IntroActivity.EXTRA_CREDITS_ONLY, true)));
    }

    private void pickNumber(String title, int[] values, int cur, IntListener l) {
        String[] n = new String[values.length];
        int sel = 0;
        for (int k = 0; k < values.length; k++) { n[k] = values[k] + "%"; if (values[k] == cur) sel = k; }
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(n, sel, (d, w) -> {
            l.on(values[w]);
            d.dismiss();
            buildRows();
        }).show();
    }

    private String driverLabel() {
        String chosen = chosenDriver();
        return chosen.isEmpty() ? "System" : "Imported";
    }

    private void driverDialog() {
        List<String> names = new ArrayList<>(), folders = new ArrayList<>();
        names.add("System (the phone's own driver)");
        folders.add("");
        File[] dirs = driversDir().listFiles();
        if (dirs != null) java.util.Arrays.sort(dirs);
        if (dirs != null) {
            for (File d : dirs) {
                File info = new File(d, DriverImport.INFO);
                if (!d.isDirectory() || !info.isFile()) continue;
                String nm = d.getName();
                try {
                    String j = new String(java.nio.file.Files.readAllBytes(info.toPath()), "UTF-8");
                    String n = DriverImport.field(j, "name");
                    String v = DriverImport.field(j, "version");
                    if (!n.isEmpty()) nm = n + (v.isEmpty() ? "" : " " + v);
                } catch (Exception ignored) {
                }
                names.add(nm);
                folders.add(d.getName());
            }
        }
        names.add("Import a driver (.zip / .so)...");
        String chosen = chosenDriver();
        int sel = Math.max(0, folders.indexOf(chosen));
        new AlertDialog.Builder(this).setTitle("Vulkan driver").setSingleChoiceItems(names.toArray(new String[0]), sel, (d, w) -> {
            d.dismiss();
            if (w == names.size() - 1) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_DRIVER);
            } else {
                chooseDriver(folders.get(w));
            }
        }).show();
    }

    // The left buttons: IMPORT ISO until a game is there; then PLAY, ADD DLC and SAVE OPTIONS.
    private boolean gameReady() {
        File dir = gameFolder();
        return hasAccess() && ((dir.isFile() && isIso(dir)) || new File(dir, "default.xex").isFile());
    }

    private Button bigButton(String text, boolean primary, Act a) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(true);
        b.setStateListAnimator(null);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(primary ? 0.25f : 0.1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 22 : 15);
        if (primary) {
            b.setTextColor(0xFFFFFFFF);
            GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFFE8432A, 0xFFC1272D, 0xFF7A0F14});
            pg.setCornerRadius(dp(40));
            b.setBackground(pg);
        } else {
            b.setTextColor(WHITE);
            b.setBackground(rounded(0xFF4A0C12, dp(40), 0x66FFFFFF));
        }
        b.setOnClickListener(v -> a.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(primary ? 56 : 46));
        lp.bottomMargin = dp(8);
        leftButtons.addView(b, lp);
        return b;
    }

    // ---- The disc image is checked once when it is picked: the XDVDFS volume is read, default.xex found and read
    // through, with a progress bar from 0 to 100%. PLAY shows when it is done.
    private volatile int isoPercent = -1;       // -1 = no check running
    private volatile String isoMessage = "";
    private String isoKey(File f) {
        return f.getPath() + ":" + f.length() + ":" + f.lastModified();
    }
    private boolean isoChecked(File f) {
        return isoKey(f).equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString("iso_checked", ""));
    }
    private void startIsoCheck(final File f) {
        if (isoPercent >= 0) return;
        isoPercent = 0;
        isoMessage = "Reading the disc image...";
        updateLeft();
        final Handler h = new Handler(android.os.Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (isoPercent < 0) return;
            if (isoBar != null) {
                isoBar.setProgress(isoPercent);
                isoText.setText(isoMessage + "  " + isoPercent + "%");
            }
            h.postDelayed(tick[0], 100);
        };
        h.post(tick[0]);
        new Thread(() -> {
            boolean ok = IsoCheck.run(f, (pct, msg) -> {
                isoPercent = pct;
                isoMessage = msg;
            });
            runOnUiThread(() -> {
                isoPercent = -1;
                if (ok) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("iso_checked", isoKey(f)).apply();
                } else {
                    Toast.makeText(this, "This does not look like an Xbox 360 game disc image (default.xex not found). You can still try Play.", Toast.LENGTH_LONG).show();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("iso_checked", isoKey(f)).apply();
                }
                updateLeft();
            });
        }, "iso-check").start();
    }

    private void updateLeft() {
        if (leftButtons == null) return;
        leftButtons.removeAllViews();
        File chosen = gameFolder();
        if (hasAccess() && chosen.isFile() && isIso(chosen) && !isoChecked(chosen) && isoPercent < 0) {
            startIsoCheck(chosen);
            return;
        }
        if (isoPercent >= 0) {
            isoText = new TextView(this);
            isoText.setTextColor(0xFFFFFFFF);
            isoText.setTextSize(14);
            isoText.setText(isoMessage + "  " + isoPercent + "%");
            isoBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            isoBar.setMax(100);
            isoBar.setProgress(isoPercent);
            isoBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFD3202A));
            leftButtons.addView(isoText);
            leftButtons.addView(isoBar, new LinearLayout.LayoutParams(-1, dp(14)));
            return;
        }
        if (!hasAccess()) {
            bigButton("Give file access", true, this::giveAccess);
        } else if (!gameReady()) {
            bigButton("Choose game files", true, this::showGameChooser);
        } else {
            bigButton("Play", true, this::play);
            bigButton("DLC / Title update", false, this::showDlcManager);
            bigButton("Save options", false, () -> {
                gp.edit().putString("folder", gameFolder().getPath()).putString("args", argsEdit.getText().toString()).apply();
                Toast.makeText(this, "Options saved", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---- The sections: the same controls and callbacks as before, in groups.

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }


    private View sectionFiles() {
        LinearLayout c = column();
        c.addView(label("Game folder, or the path of a game disc image (.iso)"));
        c.addView(folderEdit, matchWrap());
        c.addView(button("BROWSE FOR GAME .ISO", false, v -> pickIso()));
        c.addView(button("1. GIVE FILE ACCESS", false, v -> giveAccess()));
        c.addView(button("2. CHECK GAME FOLDER", false, v -> refresh()));
        c.addView(label("Extra arguments (one per line, optional, e.g. --log_level=debug)"));
        c.addView(argsEdit, matchWrap());
        return c;
    }

    private View sectionTouch(SharedPreferences prefs) {
        LinearLayout c = column();
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        String[] modeNames = {"Automatic (hidden while a controller is connected)", "Always show", "Never show"};
        for (int k = 0; k < 3; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(modeNames[k]);
            rb.setId(1000 + k);
            tint(rb);
            modes.addView(rb);
        }
        modes.check(1000 + touchMode);
        modes.setOnCheckedChangeListener((g, id) -> {
            touchMode = id - 1000;
            prefs.edit().putInt("touch_mode", touchMode).apply();
        });
        c.addView(modes);
        c.addView(seek("Size", 60, 150, touchSize, v -> {
            touchSize = v;
            prefs.edit().putInt("touch_size", v).apply();
        }, "%"));
        c.addView(seek("Opacity", 20, 100, touchOpacity, v -> {
            touchOpacity = v;
            prefs.edit().putInt("touch_opacity", v).apply();
        }, "%"));
        CheckBox vib = new CheckBox(this);
        vib.setText("Vibrate when a button is pressed");
        tint(vib);
        vib.setChecked(touchVibrate);
        vib.setOnCheckedChangeListener((b, on) -> {
            touchVibrate = on;
            prefs.edit().putBoolean("touch_vibrate", on).apply();
        });
        c.addView(vib);
        c.addView(button("EDIT TOUCH LAYOUT (move and resize)", false, v -> {
            Intent e = new Intent(this, TouchEditActivity.class);
            e.putExtra(TouchEditActivity.EXTRA_LOOK, new int[] {touchSize, touchOpacity});
            startActivity(e);
        }));
        return c;
    }

    private View sectionMonitor(SharedPreferences prefs) {
        LinearLayout c = column();
        c.addView(note("Shown in the game's top-left corner. Nothing ticked = the monitor is off."));
        String[] fpsNames = {"FPS", "Frame time (ms)", "Battery temperature"};
        int[] fpsBits = {FpsOverlay.SHOW_FPS, FpsOverlay.SHOW_FRAME_TIME, FpsOverlay.SHOW_TEMP};
        for (int k = 0; k < 3; k++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(fpsNames[k]);
            tint(cb);
            final int bit = fpsBits[k];
            cb.setChecked((fpsMask & bit) != 0);
            cb.setOnCheckedChangeListener((b, on) -> {
                fpsMask = on ? (fpsMask | bit) : (fpsMask & ~bit);
                prefs.edit().putInt("fps_mask", fpsMask).apply();
            });
            c.addView(cb);
        }
        return c;
    }

    // One row of exclusive choices stored as an int preference; returns the buttons' column.
    private void choiceGroup(LinearLayout c, SharedPreferences prefs, String title, String key, int def, String... names) {
        c.addView(label(title));
        final List<Button> btns = new ArrayList<>();
        for (int k = 0; k < names.length; k++) {
            final int kk = k;
            Button b = button("", false, null);
            b.setOnClickListener(v -> {
                prefs.edit().putInt(key, kk).apply();
                for (int m = 0; m < btns.size(); m++) btns.get(m).setText((m == kk ? "[x] " : "[  ] ") + names[m]);
            });
            btns.add(b);
            c.addView(b);
        }
        int cur = prefs.getInt(key, def);
        for (int m = 0; m < btns.size(); m++) btns.get(m).setText((m == cur ? "[x] " : "[  ] ") + names[m]);
    }

    private View sectionGraphics(SharedPreferences prefs) {
        LinearLayout c = column();
        c.addView(note("How the game is drawn on the phone's GPU. Changes apply the next time you press PLAY."));
        choiceGroup(c, prefs, "GPU PROFILE", "gpu_prof2", 0,
                "BALANCED - recommended", "FAST - more shortcuts");
        choiceGroup(c, prefs, "FRAME RATE LIMIT", "fps_limit", 0,
                "GAME'S OWN PACE", "LOCK AT 30 FPS", "LOCK AT 60 FPS");
        choiceGroup(c, prefs, "RESOLUTION", "res_scale", 0,
                "720P (1x) - recommended", "1440P (2x) - very heavy");
        choiceGroup(c, prefs, "UPSCALER", "upscaler", 0,
                "OFF (bilinear)", "SGSR - Snapdragon sharpening upscaler");
        choiceGroup(c, prefs, "TEXTURE QUALITY", "tex_quality", 0,
                "HIGH - full size", "MEDIUM - half size top level", "LOW - quarter size");
        choiceGroup(c, prefs, "TEXTURE FILTERING", "aniso", 0,
                "GAME'S OWN", "2X", "4X", "8X", "16X");
        choiceGroup(c, prefs, "CPU", "big_cores", 1,
                "OFF", "RUN ON THE FAST (BIG) CORES - recommended");
        c.addView(note("Not available yet: 360p / 480p / 540p / 1080p, dynamic resolution, frame generation and a crowd "
                + "reduction mode. The engine here can only draw at 1x (720p) or 2x (1440p), and the other options need "
                + "game-specific work that is not done."));
        return c;
    }

    // ---- Vulkan driver: the phone's own, or a Turnip (or other) driver package the player imported.
    private LinearLayout driverList;
    private TextView driverStatus;
    private static final int REQ_DRIVER = 7103;

    private File driversDir() {
        return new File(getFilesDir(), "drivers");
    }

    private View sectionDriver() {
        LinearLayout c = column();
        c.addView(note("Optional. Import a Turnip driver (a .zip made for Adreno phones, or its .so) and choose it here. "
                + "If a driver fails or crashes the game, the launcher goes back to the phone's driver by itself."));
        driverStatus = new TextView(this);
        driverStatus.setTextColor(SILVER);
        c.addView(driverStatus);
        driverList = column();
        c.addView(driverList);
        c.addView(button("IMPORT DRIVER (.ZIP / .SO)", false, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_DRIVER);
        }));
        refreshDrivers();
        return c;
    }

    private String chosenDriver() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("driver", "");
    }

    private void refreshDrivers() {
        if (driverList == null) return;
        driverList.removeAllViews();
        String chosen = chosenDriver();
        File[] dirs = driversDir().listFiles();
        if (dirs != null) java.util.Arrays.sort(dirs);
        boolean found = false;
        driverList.addView(button((chosen.isEmpty() ? "[x] " : "[  ] ") + "PHONE'S OWN DRIVER", false, v -> chooseDriver("")));
        if (dirs != null) {
            for (File d : dirs) {
                File info = new File(d, DriverImport.INFO);
                if (!d.isDirectory() || !info.isFile()) continue;
                String name = d.getName();
                try {
                    String j = new String(java.nio.file.Files.readAllBytes(info.toPath()), "UTF-8");
                    String n = DriverImport.field(j, "name");
                    String v = DriverImport.field(j, "version");
                    if (!n.isEmpty()) name = n + (v.isEmpty() ? "" : " " + v);
                } catch (Exception ignored) {
                }
                final String folder = d.getName();
                boolean on = folder.equals(chosen);
                found |= on;
                driverList.addView(button((on ? "[x] " : "[  ] ") + name.toUpperCase(java.util.Locale.ROOT), false, v -> chooseDriver(folder)));
            }
        }
        driverStatus.setText(chosen.isEmpty() || !found ? "Using: the phone's own driver" : "Using an imported driver");
        if (!chosen.isEmpty() && !found) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("driver", "").apply();
        if (found) {
            driverList.addView(button("REMOVE THE CHOSEN DRIVER", false, v -> {
                DriverImport.deleteTree(new File(driversDir(), chosenDriver()));
                chooseDriver("");
            }));
        }
    }

    private void chooseDriver(String folder) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("driver", folder).apply();
        refreshDrivers();
        if (rowsBox != null) buildRows();
    }

    // A trial file left from the last run means the chosen driver did not last 25 s: go back to the phone's driver.
    private void checkDriverTrial() {
        File trial = new File(getFilesDir(), "driver_trial");
        if (trial.exists()) {
            trial.delete();
            if (!chosenDriver().isEmpty()) {
                chooseDriver("");
                Toast.makeText(this, "The chosen Vulkan driver did not work (the game stopped within 25 s). Back to the phone's own driver.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // A trial file left from the last run means the game stopped within 40 s with DLC on: offer to turn it all off.
    private void checkDlcTrial() {
        File trial = new File(getFilesDir(), "dlc_trial");
        if (!trial.exists()) return;
        trial.delete();
        boolean any = false;
        for (DlcInfo i : dlcList()) any |= i.enabled;
        if (!any) return;
        new AlertDialog.Builder(this).setTitle("The game stopped after starting")
                .setMessage("It stopped within 40 seconds of starting with DLC / title update on. Turn them all off to see if the game starts without them? You can turn each one back on in the DLC screen.")
                .setPositiveButton("Turn all off", (d, w) -> {
                    for (DlcInfo i : dlcList()) {
                        if (!i.enabled) continue;
                        File to = new File(disabledDir(), i.file.getName());
                        to.delete();
                        i.file.renameTo(to);
                    }
                    updateDlcStatus();
                    if (rowsBox != null) buildRows();
                })
                .setNegativeButton("Keep them", null).show();
    }

    private View sectionDlc() {
        LinearLayout c = column();
        c.addView(dlcStatus);
        c.addView(button("MANAGE DLC / TITLE UPDATE", false, v -> showDlcManager()));
        updateDlcStatus();
        return c;
    }

    private View sectionLogs() {
        LinearLayout c = column();
        c.addView(note("Copies the game's logs to Download/WWE2K15_logs."));
        c.addView(button("COPY LOGS TO DOWNLOAD", false, v -> copyLogs()));
        return c;
    }


    // ---- Widgets
    private static final String LINK_GITHUB = "https://github.com/Geeeganeeega/wwe2k15-recomp";
    private static final String LINK_YOUTUBE = IntroActivity.YOUTUBE;
    private static final String LINK_FRIEND = IntroActivity.YOUTUBE_FRIEND;
    private static final String LINK_WEBSITE = IntroActivity.WEBSITE;
    private static final String LINK_TELEGRAM = IntroActivity.TELEGRAM;
    private String[] sectionNames = {};

    private LinearLayout.LayoutParams navParams(float u) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(dp(40), (int) (60 * u)));
        lp.bottomMargin = Math.max(dp(5), (int) (12 * u));
        return lp;
    }

    // "YouTube ↗" style buttons; a link that is not set is not shown.
    private void addLink(LinearLayout row, String text, String url, float u) {
        if (url == null || url.isEmpty()) return;
        Button b = new Button(this);
        b.setText(text + " ↗");
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(11), 20 * u));
        b.setStateListAnimator(null);
        b.setBackground(rounded(0xB0161618, (int) (8 * u), 0x40FFFFFF));
        b.setPadding((int) (16 * u), 0, (int) (16 * u), 0);
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, "No app can open " + url, Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Math.max(dp(30), (int) (44 * u)));
        lp.leftMargin = (int) (10 * u);
        row.addView(b, lp);
    }

    // The logo: assets/launcher_logo.png if present (your own artwork), else "WWE 2K15" in type with the subtitle.
    private View logoView(float u) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        try (InputStream in = getAssets().open("launcher_logo.png")) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(70));
            box.addView(iv, new LinearLayout.LayoutParams(-2, -2));
        } catch (Exception e) {
            TextView title = new TextView(this);
            title.setText("WWE 2K15");
            title.setTextColor(WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setLetterSpacing(0.08f);
            box.addView(title);
        }
        TextView sub = new TextView(this);
        sub.setText("Xbox 360 \u00B7 natively recompiled for Android");
        sub.setTextColor(SILVER);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(sub);
        return box;
    }

    // The background picture: fills the whole window (center-crop keeps its proportions), a light dark overlay on top.
    private View background() {
        FrameLayout f = new FrameLayout(this);
        try (InputStream in = getAssets().open("launcher_bg.jpg")) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } catch (Exception e) {
            // no picture (android/assets/launcher_bg.jpg): a WWE 2K15 red background
            View red = new View(this);
            red.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {0xFFB0101A, 0xFF5E0910, 0xFF1A0306}));
            f.addView(red, new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x66200005, 0x22000000, 0x55200005}));
        f.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private Button navButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(true);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(26), 0, dp(12), 0);
        b.setStateListAnimator(null);
        b.setTextColor(WHITE);
        return b;
    }

    private void stylePlay(Button b) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFFE3141C, 0xFF9A0D12, 0xFF3C0A0D});
        d.setCornerRadius(dp(6));
        d.setStroke(dp(2), 0xFFFF3B3B);
        b.setBackground(d);
    }

    private void styleNav(Button b, boolean selected) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                selected ? new int[] {0xF0861018, 0xF0560A10} : new int[] {0xF0420B11, 0xF02C070B});
        base.setCornerRadius(dp(6));
        base.setStroke(dp(1), selected ? 0x99FF6B6B : 0x44FF8A8A);
        if (selected) {
            android.graphics.drawable.ColorDrawable bar = new android.graphics.drawable.ColorDrawable(WHITE);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {base, bar});
            ld.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
            ld.setLayerWidth(1, dp(4));
            b.setBackground(ld);
        } else {
            b.setBackground(base);
        }
        b.setTextColor(selected ? WHITE : SILVER);
    }


    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }



    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFFB08A8E);
        e.setTextColor(WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setBackground(rounded(0xE61A1A1F, dp(6), 0x40FFFFFF));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private void tint(android.widget.CompoundButton b) {
        b.setTextColor(WHITE);
        b.setButtonTintList(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {RED, SILVER}));
        b.setPadding(dp(6), dp(8), 0, dp(8));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(SILVER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(8), 0, dp(6));
        return t;
    }

    private Button button(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(true);
        b.setTextColor(WHITE);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.08f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 18 : 14);
        b.setStateListAnimator(null);
        b.setBackground(rounded(primary ? RED : 0xE61A1A1F, dp(6), primary ? 0xFFFF4A52 : 0x40FFFFFF));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(primary ? 58 : 50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private interface IntListener { void on(int v); }

    private View seek(String name, int min, int max, int value, IntListener l, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        TextView t = new TextView(this);
        t.setTextColor(WHITE);
        t.setText(name + ": " + value + unit);
        SeekBar sb = new SeekBar(this);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(RED));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(RED));
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(name + ": " + (p + min) + unit);
                if (fromUser) l.on(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(t);
        box.addView(sb);
        return box;
    }


    // ---- DLC: the files picked are copied into <app files>/dlc/ (Android/data/<package>/files/dlc).
    private File dlcDir() {
        File files = getExternalFilesDir(null);
        if (files == null) files = getFilesDir();
        File d = new File(files, "dlc");
        d.mkdirs();
        return d;
    }

    private File disabledDir() {
        File d = new File(dlcDir(), "disabled");
        d.mkdirs();
        return d;
    }

    private List<DlcInfo> dlcList() {
        List<DlcInfo> all = new ArrayList<>();
        File[] on = dlcDir().listFiles();
        if (on != null) for (File f : on) {
            DlcInfo i = f.isFile() ? DlcInfo.read(f) : null;
            if (i != null) all.add(i);
        }
        File[] off = disabledDir().listFiles();
        if (off != null) for (File f : off) {
            DlcInfo i = f.isFile() ? DlcInfo.read(f) : null;
            if (i != null) {
                i.enabled = false;
                all.add(i);
            }
        }
        java.util.Collections.sort(all, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return all;
    }

    private void updateDlcStatus() {
        int on = 0, off = 0, tu = 0;
        for (DlcInfo i : dlcList()) {
            if (i.isUpdate()) {
                if (i.enabled) tu++;
            } else if (i.enabled) on++;
            else off++;
        }
        dlcStatus.setText(on + off + tu == 0 ? "No DLC imported." : on + " DLC on, " + off + " off" + (tu > 0 ? ", title update on" : "") + ".");
    }

    // The list of imported packages, built once as a dialog: switching one off/on or deleting it changes that row in
    // place (no flicker, the scroll position stays). Title updates have a section of their own.
    private AlertDialog dlcDialog;
    private LinearLayout dlcCol;

    private void showDlcManager() {
        if (dlcDialog != null && dlcDialog.isShowing()) {
            fillDlcList();
            return;
        }
        ScrollView sv = new ScrollView(this);
        dlcCol = new LinearLayout(this);
        dlcCol.setOrientation(LinearLayout.VERTICAL);
        dlcCol.setPadding(dp(14), dp(8), dp(14), dp(8));
        sv.addView(dlcCol);
        fillDlcList();
        dlcDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("DLC and title update")
                .setView(sv)
                .setPositiveButton("Import files", null)
                .setNegativeButton("Close", null)
                .create();
        dlcDialog.setOnDismissListener(d -> {
            dlcDialog = null;
            updateDlcStatus();
            if (rowsBox != null) buildRows();
        });
        dlcDialog.show();
        // "Import files" must not close the list.
        dlcDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> pickDlc());
    }

    private void fillDlcList() {
        dlcCol.removeAllViews();
        List<DlcInfo> all = dlcList();
        List<DlcInfo> dlcs = new ArrayList<>(), updates = new ArrayList<>();
        for (DlcInfo i : all) (i.isUpdate() ? updates : dlcs).add(i);
        dlcCol.addView(sectionTitle("DLC"));
        if (dlcs.isEmpty()) dlcCol.addView(dlcNote("No DLC imported. Use Import files (the packages from the console's Content folder)."));
        for (DlcInfo i : dlcs) dlcCol.addView(dlcRow(i));
        dlcCol.addView(sectionTitle("TITLE UPDATE"));
        if (updates.isEmpty()) dlcCol.addView(dlcNote("No title update imported."));
        for (DlcInfo i : updates) dlcCol.addView(dlcRow(i));
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFD3202A);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.15f);
        v.setPadding(0, dp(10), 0, dp(6));
        return v;
    }

    private TextView dlcNote(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFC9A3A7);
        v.setTextSize(12);
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    // One package: name, size, an on/off switch and a delete button. The switch moves the file between dlc/ and
    // dlc/disabled/ and only recolours its own row.
    private View dlcRow(final DlcInfo i) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackground(rounded(CARD, dp(12), 0));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(this);
        name.setText(i.title);
        name.setTextSize(15);
        TextView sub = new TextView(this);
        sub.setText((i.size / 1048576) + " MB  \u00B7  " + i.file.getName());
        sub.setTextColor(0xFFC9A3A7);
        sub.setTextSize(11);
        texts.addView(name);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(i.enabled);
        name.setTextColor(i.enabled ? 0xFFFFFFFF : 0xFF8A8A92);
        sw.setOnCheckedChangeListener((b, on) -> {
            File to = new File(on ? dlcDir() : disabledDir(), i.file.getName());
            to.delete();
            if (i.file.renameTo(to)) {
                i.file = to;
                i.enabled = on;
                name.setTextColor(on ? 0xFFFFFFFF : 0xFF8A8A92);
            } else {
                b.setOnCheckedChangeListener(null);
                b.setChecked(!on);
                Toast.makeText(this, "Could not change " + i.title, Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(sw);
        Button del = button("DELETE", false, v -> new AlertDialog.Builder(this).setTitle("Delete " + i.title + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    i.file.delete();
                    ((ViewGroup) row.getParent()).removeView(row);
                    updateDlcStatus();
                }).setNegativeButton("Cancel", null).show());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(84), dp(38));
        bp.leftMargin = dp(8);
        row.addView(del, bp);
        return row;
    }

    private static boolean isIso(File f) {
        return f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso");
    }

    // One place to choose the game: browse for the folder, browse for the .iso, or type a path.
    private void showGameChooser() {
        final String[] items = {"Choose the game folder (the one with default.xex)", "Choose an .iso file", "Type a path"};
        new AlertDialog.Builder(this).setTitle("Game files")
                .setItems(items, (d, which) -> {
                    if (which == 0) pickFolder();
                    else if (which == 1) pickIso();
                    else {
                        EditText e = field("Game folder or .iso path");
                        e.setText(folderEdit.getText());
                        new AlertDialog.Builder(this).setTitle("Game folder or .iso path").setView(e)
                                .setPositiveButton("OK", (d2, w) -> { folderEdit.setText(e.getText()); refresh(); })
                                .setNegativeButton("Cancel", null).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // Opens the system file picker on the phone's internal storage (Download folder) to choose the game folder.
    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        } catch (Throwable ignored) { }
        startActivityForResult(i, REQ_FOLDER);
    }

    // The ISO is read in place (no copy: it is several GB); a picked file in the phone's own storage becomes its path.
    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        } catch (Throwable ignored) { }
        startActivityForResult(i, REQ_ISO);
    }

    // content://com.android.externalstorage.documents/tree/primary:Download%2FWWE 2K15 -> /sdcard/Download/WWE 2K15
    private String treePathOf(Uri u) {
        if (!"com.android.externalstorage.documents".equals(u.getAuthority())) return null;
        String id;
        try { id = android.provider.DocumentsContract.getTreeDocumentId(u); } catch (Exception e) { return null; }
        int c = id.indexOf(':');
        if (c <= 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        String base = vol.equals("primary") ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    private String pathOf(Uri u) {
        String auth = u.getAuthority();
        // The picker's "Downloads" and "Images/Recent" shortcuts use other providers than internal storage.
        if ("com.android.providers.downloads.documents".equals(auth)) {
            try {
                String id = android.provider.DocumentsContract.getDocumentId(u);
                if (id.startsWith("raw:")) return id.substring(4);  // raw:/storage/emulated/0/Download/x.iso
            } catch (Exception ignored) { }
            String p = dataColumn(u);
            if (p != null) return p;
        }
        if ("com.android.providers.media.documents".equals(auth) || "media".equals(auth)) {
            String p = dataColumn(u);
            if (p != null) return p;
        }
        // content://com.android.externalstorage.documents/document/primary:Download/x.iso -> /sdcard/Download/x.iso
        if ("com.android.externalstorage.documents".equals(auth)) {
            String id = android.provider.DocumentsContract.getDocumentId(u);
            int c = id.indexOf(':');
            if (c > 0) {
                String vol = id.substring(0, c), rel = id.substring(c + 1);
                if (vol.equals("primary")) return Environment.getExternalStorageDirectory() + "/" + rel;
                return "/storage/" + vol + "/" + rel;
            }
        }
        return null;
    }

    // The file's real path when its provider shares one (the _data column); null otherwise.
    private String dataColumn(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[] {"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && new File(p).isFile()) return p;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void pickDlc() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_DLC);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_ISO && result == RESULT_OK && data != null && data.getData() != null) {
            String path = pathOf(data.getData());
            if (path == null) {
                Toast.makeText(this, "Could not find that file's path. Open the picker's menu, choose the phone's Internal storage, and pick the .iso there (or use Type a path).", Toast.LENGTH_LONG).show();
            } else {
                folderEdit.setText(path);
                refresh();
            }
            return;
        }
        if (req == REQ_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            String path = treePathOf(data.getData());
            if (path == null) {
                Toast.makeText(this, "Pick a folder in the phone's storage or on an SD card (a cloud or app folder cannot be read in place).", Toast.LENGTH_LONG).show();
                return;
            }
            // Accept the folder itself, or a folder inside it that holds default.xex (e.g. picked one level too high).
            File f = new File(path);
            if (!new File(f, "default.xex").exists()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) if (k.isDirectory() && new File(k, "default.xex").exists()) { f = k; break; }
            }
            folderEdit.setText(f.getPath());
            if (!new File(f, "default.xex").exists())
                Toast.makeText(this, "default.xex was not found in that folder. Pick the folder that contains it.", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (req == REQ_DRIVER && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri u = data.getData();
            Toast.makeText(this, "Importing the driver...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                DriverImport.Result r;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    r = DriverImport.run(in, displayName(u), driversDir());
                } catch (Exception e) {
                    r = new DriverImport.Result();
                    r.message = "Could not read the file: " + e.getMessage();
                }
                final DriverImport.Result res = r;
                runOnUiThread(() -> {
                    if (res.ok) chooseDriver(res.folder);
                    Toast.makeText(this, res.message, Toast.LENGTH_LONG).show();
                });
            }, "driver-import").start();
            return;
        }
        if (req != REQ_DLC || result != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Toast.makeText(this, "Copying " + uris.size() + " file(s)...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0, bad = 0;
            File dir = dlcDir();
            for (Uri u : uris) {
                String name = displayName(u);
                File out = new File(dir, name);
                try (InputStream in = getContentResolver().openInputStream(u);
                     OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } catch (Exception e) {
                    out.delete();
                    bad++;
                    continue;
                }
                if (DlcInfo.read(out) == null) {  // not a console package
                    out.delete();
                    bad++;
                } else {
                    new File(dir, "disabled/" + name).delete();  // an older copy that was switched off
                    ok++;
                }
            }
            final int done = ok, notOk = bad;
            runOnUiThread(() -> {
                Toast.makeText(this, done + " imported" + (notOk > 0 ? ", " + notOk + " were not DLC / title update packages" : ""),
                        Toast.LENGTH_LONG).show();
                updateDlcStatus();
                showDlcManager();
            });
        }, "dlc-import").start();
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) name = c.getString(ix);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "dlc_" + System.currentTimeMillis();
        return name.replace('/', '_');
    }

    private void removeDlc() {
        new AlertDialog.Builder(this).setTitle("Remove all imported DLC?")
                .setPositiveButton("Remove", (d, w) -> {
                    File[] list = dlcDir().listFiles();
                    if (list != null) for (File f : list) f.delete();
                    updateDlcStatus();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void giveAccess() {
        if (hasAccess()) {
            Toast.makeText(this, "File access is already on.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private File gameFolder() {
        return new File(folderEdit.getText().toString().trim());
    }

    // The APK is built for one kind of game: the ISO / original default.xex (version name has "original"), or the
    // patched default.xex (about 25 MB). The two are not interchangeable (the game stops at once on a mismatch).
    // This build's game code is recompiled from WWE 2K15 with Title Update 4 applied, so the game needs that title
    // update's default.xexp: beside default.xex in the game folder, or imported in the DLC screen (an ISO needs the
    // imported one).
    private String buildMismatch() {
        File dir = gameFolder();
        boolean tuImported = false;
        for (DlcInfo i : dlcList()) if (i.isUpdate() && i.enabled) tuImported = true;
        if (tuImported) return null;
        if (dir.isFile() && isIso(dir))
            return "WWE 2K15 needs Title Update 4. An ISO cannot hold it: import the title update package in DLC.";
        if (new File(dir, "default.xex").isFile() && !new File(dir, "default.xexp").isFile())
            return "WWE 2K15 needs Title Update 4: put its default.xexp next to default.xex, or import the title update "
                    + "package in DLC.";
        return null;
    }

    private void refresh() {
        if (status == null) return;
        StringBuilder s = new StringBuilder();
        s.append("File access: ").append(hasAccess() ? "on" : "OFF (tap 1)").append('\n');
        File dir = gameFolder();
        File xex = new File(dir, "default.xex");
        if (dir.isFile() && isIso(dir)) {
            s.append("Game disc image: ").append(dir.getName()).append(" (").append(dir.length() / (1024 * 1024)).append(" MB)");
        } else if (!dir.isDirectory()) {
            s.append("Game folder or .iso: not found");
        } else if (!xex.isFile()) {
            s.append("Game folder: found, but no default.xex in it");
        } else {
            s.append("Game folder: default.xex found (").append(xex.length() / (1024 * 1024)).append(" MB)");
        }
        String mm = buildMismatch();
        if (mm != null) s.append('\n').append(mm);
        status.setText(s.toString());
        updateLeft();
    }

    private void play() {
        refresh();
        if (!hasAccess()) {
            Toast.makeText(this, "Give file access first (button 1).", Toast.LENGTH_LONG).show();
            return;
        }
        String mismatch = buildMismatch();
        if (mismatch != null) {
            new AlertDialog.Builder(this).setTitle("Title Update 4 missing").setMessage(mismatch)
                    .setPositiveButton("OK", null).show();
            return;
        }
        File dir = gameFolder();
        boolean iso = dir.isFile() && isIso(dir);
        if (!iso && !new File(dir, "default.xex").isFile()) {
            Toast.makeText(this, "default.xex is not in that folder (or pick a .iso file).", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("folder", dir.getPath())
                .putString("args", argsEdit.getText().toString())
                .apply();

        List<String> args = new ArrayList<>();
        args.add("--game_data_root=" + dir.getPath());
        SharedPreferences gp = getSharedPreferences(PREFS, MODE_PRIVATE);
        args.add("--wwe2k15_gpu_profile=" + (gp.getInt("gpu_prof2", 0) >= 1 ? 2 : 1));
        args.add("--wwe2k15_fps_limit=" + new int[] {0, 30, 60}[gp.getInt("fps_limit", 0)]);
        final int[] heights = {360, 480, 540, 720, 1080, 1440};
        int resH = heights[Math.max(0, Math.min(5, gp.getInt("res_h", gp.getInt("res_scale", 0) == 1 ? 5 : 3)))];
        final boolean perfMode = gp.getInt("perf_mode", 0) == 1;
        if (perfMode) resH = Math.min(resH, 540);
        final int resScale = resH > 720 ? 2 : 1;
        args.add("--resolution_scale=" + resScale);
        // the draw height below the resolution scale's own (720 x scale); 0 = draw at the scale's size
        args.add("--gpu_native_truth_draw_height=" + (resH >= 720 * resScale ? 0 : resH));
        if (gp.getInt("upscaler", 0) == 1 || perfMode) args.add("--present_effect=sgsr");
        args.add("--gpu_debug_texture_min_lod=" + new String[] {"0", "1", "2"}[gp.getInt("tex_quality", 2)]);
        args.add("--anisotropic_override=" + new int[] {-1, 2, 3, 4, 5}[gp.getInt("aniso", 1)]);
        args.add("--vulkan_log_debug_messages=false");  // the Adreno driver's ~1,000,000 debug warnings per match cost speed
        float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        try {
            for (android.view.Display.Mode m : getWindowManager().getDefaultDisplay().getSupportedModes()) hz = Math.max(hz, m.getRefreshRate());
        } catch (Throwable ignored) {
        }
        args.add("--wwe2k15_display_hz=" + Math.round(hz));
        try {
            boolean anyOn = false;
            for (DlcInfo i : dlcList()) anyOn |= i.enabled;
            File t = new File(getFilesDir(), "dlc_trial");
            if (anyOn) t.createNewFile();
            else t.delete();
        } catch (Exception ignored) {
        }
        args.add("--wwe2k15_dlc_dir=" + dlcDir().getPath());
        boolean haveUpdate = false;
        for (DlcInfo i : dlcList()) if (i.isUpdate() && i.enabled) haveUpdate = true;
        File updateDir = new File(dlcDir().getParentFile(), "update");
        if (haveUpdate) {
            updateDir.mkdirs();
            args.add("--wwe2k15_update_dir=" + updateDir.getPath());
            args.add("--update_data_root=" + updateDir.getPath());
        } else if (updateDir.isDirectory()) {
            DriverImport.deleteTree(updateDir);  // the title update was turned off or deleted
        }
        // WWE 2K15 (framerate_hook.cpp): entrances / victory scenes at 60 instead of the console's 30; the game goes
        // back to 30 by itself when the phone cannot hold 60 there (wwe2k15_60fps_auto).
        args.add("--wwe2k15_60fps=" + (gp.getInt("scenes60", 1) == 1));
        args.add("--vsync=" + (gp.getInt("vsync", 0) == 1));
        args.add("--framegen=" + (gp.getInt("framegen", 0) == 1));
        final int shape = gp.getInt("screen_shape", 0);  // 0 = 16:9 (the game's own), 1-4 = wider shapes, 5 = fill
        args.add("--present_letterbox=" + (shape != 5));
        if (shape >= 1 && shape <= 4) {
            final int[][] ratios = {{18, 9}, {39, 20}, {20, 9}, {21, 9}};  // 19.5:9 written as 39:20
            args.add("--present_aspect_x=" + ratios[shape - 1][0]);
            args.add("--present_aspect_y=" + ratios[shape - 1][1]);
        }
        args.add("--wwe2k15_big_cores=" + (gp.getInt("big_cores", 1) == 1));
        for (String line : argsEdit.getText().toString().split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) args.add(line);
        }
        String driver = chosenDriver();
        if (!driver.isEmpty()) {
            File dd = new File(driversDir(), driver);
            try {
                String j = new String(java.nio.file.Files.readAllBytes(new File(dd, DriverImport.INFO).toPath()), "UTF-8");
                String lib = DriverImport.field(j, "library");
                File trial = new File(getFilesDir(), "driver_trial");
                trial.createNewFile();
                args.add("--wwe2k15_android_driver=" + lib);
                args.add("--wwe2k15_android_driver_dir=" + dd.getAbsolutePath() + "/");
                args.add("--wwe2k15_android_lib_dir=" + getApplicationInfo().nativeLibraryDir + "/");
                args.add("--wwe2k15_android_tmp_dir=" + getCacheDir().getAbsolutePath() + "/");
                args.add("--wwe2k15_android_driver_trial=" + trial.getAbsolutePath());
            } catch (Exception e) {
                Toast.makeText(this, "The chosen driver could not be read; using the phone's driver.", Toast.LENGTH_LONG).show();
            }
        }
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_ARGS, args.toArray(new String[0]));
        i.putExtra(GameActivity.EXTRA_TOUCH, new int[] {touchMode, touchSize, touchOpacity, touchVibrate ? 1 : 0});
        i.putExtra(GameActivity.EXTRA_FPS, fpsMask);
        // The credits card comes first (about 7.5 seconds), then the game starts.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("from_game", true).commit();
        startActivity(new Intent(this, CreditsActivity.class).putExtra(CreditsActivity.EXTRA_NEXT, i));
    }

    // The game's logs (<app files>/logs, and anything it wrote next to them) copied to Download/WWE2K15_logs.
    private void copyLogs() {
        if (!hasAccess()) {
            Toast.makeText(this, "Give file access first (button 1).", Toast.LENGTH_LONG).show();
            return;
        }
        File files = getExternalFilesDir(null);
        if (files == null) files = getFilesDir();
        File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WWE2K15_logs");
        out.mkdirs();
        int copied = 0;
        File logs = new File(files, "logs");
        File[] list = logs.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && copyFile(f, new File(out, f.getName()))) copied++;
            }
        }
        // The title update's executable patch and each DLC's catalog files, so a DLC the game refuses can be traced.
        File xexp = new File(files, "update/default.xexp");
        if (xexp.isFile()) copyFile(xexp, new File(out, "tu_default.xexp"));
        File packs = new File(files, ".local/share/wwe2k15/0000000000000000");
        File[] titles = packs.listFiles();
        if (titles != null) {
            for (File t : titles) {
                File[] dlcs = new File(t, "00000002").listFiles();
                if (dlcs == null) continue;
                for (File d : dlcs) {
                    File[] infos = new File(d, "info").listFiles();
                    if (infos == null) continue;
                    String tag = d.getName().length() > 8 ? d.getName().substring(0, 8) : d.getName();
                    for (File i : infos) {
                        if (i.isFile() && i.length() < 1_000_000) copyFile(i, new File(out, "dlcinfo_" + tag + "_" + i.getName()));
                    }
                }
            }
        }
        Toast.makeText(this, copied > 0 ? copied + " log file(s) copied to Download/WWE2K15_logs"
                : "No logs yet (" + logs.getPath() + ")", Toast.LENGTH_LONG).show();
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
