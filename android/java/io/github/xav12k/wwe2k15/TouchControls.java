package io.github.xav12k.wwe2k15;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * On-screen touch controls laid out for WWE 2K15 (not a generic Xbox pad): every button is named after what it does in
 * a match. They drive a virtual gamepad in libmain (touch_pad.cpp) that joins player 1 next to any real controller.
 *
 * Left: movement stick (push it to the edge to run), RUN (tap to switch running on or off) and CLIMB / TAG. Right: STRIKE, GRAPPLE, WHIP / PIN,
 * FINISHER, REVERSAL, LIMB / ITEM and a small right stick (reposition the opponent; tap = store a finisher).
 * Top: BACK and PAUSE; left: the TAUNT cross pad (one pad, four zones, slide between them). The game's own menus work
 * with the stick, GRAPPLE (= A) and WHIP / PIN (= B).
 *
 * Look ("Broadcast" style, made by Shadow Xavier): dark gradient discs with a coloured ring and glow, the action in bold
 * italic and the pad button in a small coloured tag; red slanted tags for TAUNT, MOVE OPP and HIDE. Every piece is
 * drawn once into a cached Bitmap when the layout or size changes; onDraw only blits them (no new Paints or Shaders).
 *
 * Shown according to the launcher's "Touch controls" setting: automatic (hidden while a controller is connected),
 * always or never. The overlay is a panel window, because the game surface is placed on top of the activity window.
 * The arrow (top right) collapses the buttons (only it and PAUSE stay) and brings them back; holding it opens the
 * layout editor: drag any button, tap one and use − / + to resize it ("touch_layout" in the launcher's preferences).
 * "Button size" (launcher) scales everything toward its corner, so the middle of the screen stays free.
 */
final class TouchControls extends View implements InputManager.InputDeviceListener {
    // SDL_GamepadButton / SDL_GamepadAxis numbers.
    private static final int A = 0, B = 1, X = 2, Y = 3, BACK = 4, START = 6, LS = 7, RS = 8, LB = 9, RB = 10,
            UP = 11, DOWN = 12, LEFT = 13, RIGHT = 14;
    private static final int AX_LX = 0, AX_LY = 1, AX_RX = 2, AX_RY = 3, AX_LT = 4, AX_RT = 5;
    private static final int TRIGGER = 100;  // button ids >= TRIGGER are trigger axes (id - TRIGGER)

    static native boolean nativeAttach();
    static native void nativeDetach();
    static native void nativeButton(int button, boolean down);
    static native void nativeAxis(int axis, float value);

    /** A round button: position and radius in fractions of the screen height (x from the left or right edge). */
    private static final class Btn {
        final String label, sub;
        final int id, color;
        final float dfx, dfy, dfr;  // defaults
        float fx, fy, fr;
        final boolean fromRight;
        float cx, cy, r;
        int pointer = -1;
        long downTime;
        Bitmap normal, pressed;  // cached look (built in place())
        int builtFor;            // the radius the bitmaps were made for
        boolean visPressed;
        long visReleased;        // for the 80 ms spring back
        Btn(String label, String sub, int id, int color, boolean fromRight, float fx, float fy, float fr) {
            this.label = label; this.sub = sub; this.id = id; this.color = color;
            this.fromRight = fromRight; this.fx = dfx = fx; this.fy = dfy = fy; this.fr = dfr = fr;
        }
    }

    // Ring colours.
    private static final int GREEN = 0xFF22C55E, RED = 0xFFEF4444, BLUE = 0xFF3B82F6, GOLD = 0xFFEAB308,
            GREY = 0xFF9CA3AF, ORANGE = 0xFFF97316, PURPLE = 0xFF8B5CF6, BRAND = 0xFFF0162F;

    // Default layout: x / y = the centre in % of a 2400x1080 screen's width / height, d = diameter in % of the height.
    // Stored as distances from the nearer side edge in screen heights (fromRight), so the right cluster stays on the
    // right edge and the left controls on the left edge on every screen shape.
    private static final float REF_ASPECT = 2400f / 1080f;
    private static Btn at(String label, String sub, int id, int color, float x, float y, float d) {
        boolean right = x > 0.5f;
        float fx = (right ? 1f - x : x) * REF_ASPECT;
        return new Btn(label, sub, id, color, right, fx, y, d / 2f);
    }

    private final Btn[] buttons = {
            at("GRAPPLE", "A", A, GREEN, 0.895f, 0.891f, 0.163f),
            at("STRIKE", "X", X, BLUE, 0.826f, 0.763f, 0.163f),
            at("WHIP", "PIN · B", B, RED, 0.953f, 0.737f, 0.148f),
            at("FINISHER", "SIG · Y", Y, GOLD, 0.893f, 0.622f, 0.141f),
            at("REVERSAL", "RT", TRIGGER + AX_RT, PURPLE, 0.947f, 0.219f, 0.178f),
            at("LIMB", "ITEM · RB", RB, GREY, 0.833f, 0.259f, 0.107f),
            at("RUN", "TAP ON/OFF", TRIGGER + AX_LT, ORANGE, 0.197f, 0.902f, 0.119f),
            at("CLIMB", "TAG · LB", LB, GREY, 0.208f, 0.722f, 0.107f),
            at("PAUSE", "", START, BRAND, 0.300f, 0.052f, 0.070f),
            at("BACK", "", BACK, BRAND, 0.252f, 0.052f, 0.070f),
            // The TAUNT cross pad and the right stick (drawn and handled separately; listed so the editor can move
            // and resize them).
            at("", "", TAUNT_PAD, BRAND, 0.090f, 0.352f, 0.144f),
            at("", "", RS_PAD, BRAND, 0.757f, 0.902f, 0.111f),
            new Btn("HIDE", "", COLLAPSE, BRAND, true, 0.10f, 0.055f, 0.028f),  // placed 16 dp from the corner
    };
    private static final int RS_PAD = 200, COLLAPSE = 201, TAUNT_PAD = 202;  // not gamepad buttons
    // The cross pad's four directions: pressed through press() like any button, never hit-tested or drawn as discs.
    private final Btn[] padBtns = {new Btn("", "", UP, 0, false, 0, 0, 0), new Btn("", "", LEFT, 0, false, 0, 0, 0),
            new Btn("", "", RIGHT, 0, false, 0, 0, 0), new Btn("", "", DOWN, 0, false, 0, 0, 0)};
    private int padPointer = -1, padDir = -1;  // padDir: index in padBtns, -1 none
    private Btn padBtn() { return buttons[buttons.length - 3]; }
    private Btn collapseBtn() { return buttons[buttons.length - 1]; }
    private float scale = 1f;  // "Button size": small / medium (the design's own sizes) / large
    private boolean collapsed;
    private int collapsePointer = -1;
    private boolean collapseLong;
    private Btn rsBtn() { return buttons[buttons.length - 2]; }

    // Movement stick: appears where the left thumb lands (left half, lower part). Right stick: fixed and small.
    private int stickPointer = -1;
    private float stickBaseX, stickBaseY, stickX, stickY, stickR;
    private boolean running;     // stick pushed to the edge
    private boolean runToggled;  // RUN tapped on
    private boolean ltDown;      // what the game currently gets for LT
    private boolean vibrate;
    // Layout editor.
    private boolean editing;
    private Btn editSel, editDrag;
    private int editPointer = -1;
    private float editOffX, editOffY;
    private final RectF[] editBar = new RectF[4];  // −, +, RESET, DONE
    private int rsPointer = -1;
    private float rsCx, rsCy, rsR, rsX, rsY;
    private long rsDownTime;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Drawing the cached pieces: one Paint for all bitmaps (it carries the opacity setting), one for the pad's pressed arm.
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG), armLit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private Bitmap stickBase, stickThumb, stickThumbRun, rsBase, rsThumb, padBmp, padTag, rsTag, hideTag, showTag;
    private float stickHomeX, stickHomeY;
    private final RectF[] padArms = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF hideRect = new RectF();
    private final float alpha;
    private final int mode;  // 0 automatic, 1 always, 2 off
    private final Activity activity;
    private final android.content.SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attached, added, shown;
    private boolean editorOnly;  // the launcher's "Edit touch layout": no game, DONE closes the screen

    /** The layout editor on its own, for the launcher (TouchLayoutActivity). */
    static TouchControls editor(Activity activity) {
        android.content.SharedPreferences p = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        TouchControls t = new TouchControls(activity, p, 1, 0.75f);
        t.editorOnly = true;
        t.shown = true;
        t.editing = true;
        t.collapsed = false;
        return t;
    }

    static TouchControls install(Activity activity) {
        android.content.SharedPreferences p = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        int mode = p.getInt("touch_mode", 0);
        if (mode == 2) return null;
        float[] alphas = {0.30f, 0.50f, 0.75f};
        TouchControls t = new TouchControls(activity, p, mode, alphas[Math.max(0, Math.min(2, p.getInt("touch_opacity", 1)))]);
        t.start();
        return t;
    }

    private TouchControls(Activity activity, android.content.SharedPreferences prefs, int mode, float alpha) {
        super(activity);
        this.activity = activity;
        this.prefs = prefs;
        this.vibrate = prefs.getBoolean("touch_vibrate", true);
        this.scale = new float[] {0.85f, 1f, 1.15f}[Math.max(0, Math.min(2, prefs.getInt("touch_size", 1)))];
        this.dp = activity.getResources().getDisplayMetrics().density;
        this.collapsed = prefs.getBoolean("touch_collapsed", false);
        loadLayout();
        this.mode = mode;
        this.alpha = alpha;
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        small.setColor(0xCCFFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        blit.setAlpha((int) (alpha * 255));
        armLit.setColor(BRAND);
        armLit.setAlpha(110);
        setSystemUiVisibility(SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void start() {
        InputManager im = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(this, handler);
        handler.post(this::tryAttach);
    }

    /** The virtual pad can only be made once the game has started SDL's gamepad support: retry until then. */
    private void tryAttach() {
        if (attached) return;
        try {
            attached = nativeAttach();
        } catch (UnsatisfiedLinkError e) {
            return;  // libmain without touch support
        }
        if (!attached) {
            handler.postDelayed(this::tryAttach, 500);
            return;
        }
        updateVisibility();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        ((InputManager) activity.getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(this);
        releaseAll();
        if (added) {
            try { activity.getWindowManager().removeView(this); } catch (RuntimeException ignored) { }
            added = false;
        }
        if (attached) {
            nativeDetach();
            attached = false;
        }
    }

    private static boolean controllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    private void updateVisibility() {
        if (!attached) return;
        boolean want = mode == 1 || !controllerConnected();
        if (want && !added) {
            if (activity.getWindow().getDecorView().getWindowToken() == null) {
                handler.postDelayed(this::updateVisibility, 300);
                return;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.token = activity.getWindow().getDecorView().getWindowToken();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            try {
                activity.getWindowManager().addView(this, lp);
                added = true;
            } catch (RuntimeException e) {
                handler.postDelayed(this::updateVisibility, 1000);
                return;
            }
        }
        shown = want;
        setVisibility(want ? VISIBLE : GONE);
        if (!want) releaseAll();
    }

    @Override public void onInputDeviceAdded(int id) { updateVisibility(); }
    @Override public void onInputDeviceRemoved(int id) { updateVisibility(); }
    @Override public void onInputDeviceChanged(int id) { updateVisibility(); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        place();
        ring.setStrokeWidth(Math.max(2f, h / 360f));
        float bw = 0.16f * h, bh = 0.09f * h, gap = 0.02f * h, x0 = w / 2f - 2 * bw - 1.5f * gap, y0 = 0.17f * h;
        for (int i = 0; i < 4; ++i) editBar[i] = new RectF(x0 + i * (bw + gap), y0, x0 + i * (bw + gap) + bw, y0 + bh);
    }

    /** Screen positions from the fractional layout; the cached pictures are (re)made here when a size changed. */
    private void place() {
        int w = getWidth(), h = getHeight();
        if (h == 0) return;
        for (Btn b : buttons) {
            if (b.id == COLLAPSE) continue;
            float k = scale;
            b.r = b.fr * h * k;
            b.cx = b.fromRight ? w - b.fx * h * k : b.fx * h * k;
            b.cy = bottomAnchored(b) ? h - (1f - b.fy) * h * k : b.fy * h * k;
            b.cx = Math.max(b.r, Math.min(w - b.r, b.cx));  // never off the screen ("Button size" large)
            b.cy = Math.max(b.r, Math.min(h - b.r, b.cy));
        }
        Btn rs = rsBtn();
        rsR = rs.r; rsCx = rs.cx; rsCy = rs.cy;
        // Movement stick: its run edge (stickR * 1.25) is the drawn base, 26.3 % of the height at x 10.8 %, y 87 %.
        stickR = 0.1315f * h * scale / 1.25f;
        stickHomeX = Math.max(stickR * 1.25f, 0.108f * REF_ASPECT * h * scale);
        stickHomeY = Math.min(h - stickR * 1.25f, h - (1f - 0.87f) * h * scale);
        // HIDE: a slanted tag 16 dp from the top and right edges.
        float th = Math.max(28 * dp, 0.056f * h), tw = th * 2.6f;
        hideRect.set(w - 16 * dp - tw, 16 * dp, w - 16 * dp, 16 * dp + th);
        Btn cb = collapseBtn();
        cb.cx = hideRect.centerX(); cb.cy = hideRect.centerY(); cb.r = th / 2f;
        buildPictures(h);
    }

    /** Buttons in the lower part of the screen shrink toward the bottom edge, the others toward the top. */
    private static boolean bottomAnchored(Btn b) { return b.dfy > 0.4f; }

    private void loadLayout() {
        String saved = prefs.getString("touch_layout2", "");
        for (String item : saved.split(";")) {
            String[] kv = item.split(":");
            if (kv.length != 2) continue;
            String[] v = kv[1].split(",");
            try {
                int i = Integer.parseInt(kv[0]);
                if (i < 0 || i >= buttons.length || v.length != 3) continue;
                buttons[i].fx = Float.parseFloat(v[0]);
                buttons[i].fy = Float.parseFloat(v[1]);
                buttons[i].fr = Float.parseFloat(v[2]);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buttons.length; ++i) {
            Btn b = buttons[i];
            if (b.fx == b.dfx && b.fy == b.dfy && b.fr == b.dfr) continue;
            sb.append(i).append(':').append(b.fx).append(',').append(b.fy).append(',').append(b.fr).append(';');
        }
        prefs.edit().putString("touch_layout2", sb.toString()).apply();
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!shown) return false;
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        if (editing) {
            editTouch(e, action, idx);
            invalidate();
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); ++i) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return true;
    }

    private Btn hit(float x, float y) {
        Btn best = null;
        float bestD = Float.MAX_VALUE;
        for (Btn b : buttons) {
            if (b.id == RS_PAD || b.id == TAUNT_PAD || (collapsed && b.id != COLLAPSE && b.id != START)) continue;
            float d = (float) Math.hypot(x - b.cx, y - b.cy);
            if (b.id == COLLAPSE) {
                if (x >= hideRect.left - 8 * dp && x <= hideRect.right + 8 * dp && y >= hideRect.top - 8 * dp
                        && y <= hideRect.bottom + 8 * dp) d = 0;
                else continue;
            }
            if (d < b.r * 1.15f && d < bestD) { best = b; bestD = d; }
        }
        return best;
    }

    private void down(int pid, float x, float y) {
        Btn b = hit(x, y);
        if (b != null && b.id == COLLAPSE) {
            // Tap: collapse / expand. Hold: layout editor.
            collapsePointer = pid;
            collapseLong = false;
            handler.postDelayed(() -> {
                if (collapsePointer != pid) return;
                collapseLong = true;
                releaseAll();
                collapsed = false;
                editing = true;
                editSel = null;
                place();
                if (vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            }, 700);
            return;
        }
        if (collapsed && (b == null || b.id != START)) return;
        if (b == null && padPointer < 0 && inPad(x, y)) {  // TAUNT cross pad
            padPointer = pid;
            updatePad(x, y);
            return;
        }
        if (b != null && b.id == TRIGGER + AX_LT) {  // RUN: a toggle
            runToggled = !runToggled;
            updateLt();
            if (vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        if (b != null) {
            if (b.pointer < 0) press(b, true);
            b.pointer = pid;
            return;
        }
        if (rsPointer < 0 && Math.hypot(x - rsCx, y - rsCy) < rsR * 1.6f) {
            rsPointer = pid;
            rsDownTime = System.currentTimeMillis();
            rsX = x; rsY = y;
            sendRs();
            return;
        }
        if (stickPointer < 0 && x < getWidth() * 0.5f && y > getHeight() * 0.35f) {
            stickPointer = pid;
            stickBaseX = Math.max(stickR, Math.min(x, getWidth() * 0.5f - stickR));
            stickBaseY = Math.max(getHeight() * 0.35f + stickR * 0.5f, Math.min(y, getHeight() - stickR));
            stickX = x; stickY = y;
            sendStick();
        }
    }

    private void move(int pid, float x, float y) {
        if (pid == stickPointer) {
            stickX = x; stickY = y;
            sendStick();
        } else if (pid == padPointer) {
            updatePad(x, y);
        } else if (pid == rsPointer) {
            rsX = x; rsY = y;
            sendRs();
        } else {
            // Sliding a finger from one button onto another (e.g. STRIKE -> GRAPPLE) switches the press.
            for (Btn b : buttons) {
                if (b.pointer != pid) continue;
                if (Math.hypot(x - b.cx, y - b.cy) > b.r * 1.4f) {
                    Btn other = hit(x, y);
                    press(b, false);
                    b.pointer = -1;
                    if (other != null && other.pointer < 0) { other.pointer = pid; press(other, true); }
                }
                return;
            }
        }
    }

    private void up(int pid) {
        if (pid == collapsePointer) {
            collapsePointer = -1;
            if (!collapseLong) {
                releaseAll();
                collapsed = !collapsed;
                prefs.edit().putBoolean("touch_collapsed", collapsed).apply();
                if (vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }
            return;
        }
        if (pid == padPointer) {
            padPointer = -1;
            setPadDir(-1);
        } else if (pid == stickPointer) {
            stickPointer = -1;
            nativeAxis(AX_LX, 0); nativeAxis(AX_LY, 0);
            setRunning(false);
        } else if (pid == rsPointer) {
            rsPointer = -1;
            boolean tap = System.currentTimeMillis() - rsDownTime < 250 && Math.hypot(rsX - rsCx, rsY - rsCy) < rsR * 0.5f;
            nativeAxis(AX_RX, 0); nativeAxis(AX_RY, 0);
            if (tap) {  // a quick tap clicks the right stick (store a finisher)
                nativeButton(RS, true);
                handler.postDelayed(() -> nativeButton(RS, false), 80);
            }
        }
        for (Btn b : buttons) {
            if (b.pointer == pid) { b.pointer = -1; press(b, false); }
        }
    }

    private void releaseAll() {
        for (Btn b : buttons) if (b.pointer >= 0) { b.pointer = -1; press(b, false); }
        runToggled = false;
        if (padPointer >= 0) { padPointer = -1; setPadDir(-1); }
        if (stickPointer >= 0) { stickPointer = -1; nativeAxis(AX_LX, 0); nativeAxis(AX_LY, 0); }
        if (rsPointer >= 0) { rsPointer = -1; nativeAxis(AX_RX, 0); nativeAxis(AX_RY, 0); }
        setRunning(false);
        invalidate();
    }

    // ---- TAUNT cross pad: four zones by angle from the centre, a dead zone of 20 % in the middle; sliding from one
    // zone to another releases the old direction and presses the new one. ----

    private boolean inPad(float x, float y) {
        Btn p = padBtn();
        return Math.abs(x - p.cx) <= p.r * 1.15f && Math.abs(y - p.cy) <= p.r * 1.15f;
    }

    private void updatePad(float x, float y) {
        Btn p = padBtn();
        float dx = x - p.cx, dy = y - p.cy;
        int dir;
        if (Math.hypot(dx, dy) < p.r * 0.2f) dir = -1;
        else if (Math.abs(dx) > Math.abs(dy)) dir = dx < 0 ? 1 : 2;  // LEFT / RIGHT
        else dir = dy < 0 ? 0 : 3;                                    // UP / DOWN
        setPadDir(dir);
    }

    private void setPadDir(int dir) {
        if (dir == padDir) return;
        if (padDir >= 0) {
            Btn old = padBtns[padDir];
            old.pointer = -1;
            press(old, false);
        }
        padDir = dir;
        if (dir >= 0) {
            Btn now = padBtns[dir];
            now.pointer = padPointer;
            press(now, true);
        }
        invalidate();
    }

    private void press(Btn b, boolean down) {
        if (!attached) return;
        if (!down) {
            // A press must last a few frames, or the game (which reads the pad once a frame) can miss a quick tap.
            long held = System.currentTimeMillis() - b.downTime;
            if (held < 100) {
                handler.postDelayed(() -> { if (b.pointer < 0) press(b, false); }, 100 - held);
                return;
            }
        } else {
            b.downTime = System.currentTimeMillis();
        }
        if (b.id >= TRIGGER) {
            nativeAxis(b.id - TRIGGER, down ? 1f : 0f);
        } else {
            nativeButton(b.id, down);
        }
        if (down && vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void sendStick() {
        float dx = (stickX - stickBaseX) / stickR, dy = (stickY - stickBaseY) / stickR;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; }
        if (len < 0.12f) { dx = 0; dy = 0; }  // dead zone
        nativeAxis(AX_LX, dx);
        nativeAxis(AX_LY, dy);
        setRunning(len > 1.25f);  // dragged past the ring: run
    }

    private void setRunning(boolean run) {
        if (run == running) return;
        running = run;
        updateLt();
    }

    /** LT (run) is held while RUN is switched on or the stick is pushed past its ring. */
    private void updateLt() {
        boolean want = runToggled || running;
        if (want == ltDown || !attached) return;
        ltDown = want;
        nativeAxis(AX_LT, want ? 1f : 0f);
        invalidate();
    }

    // ---- layout editor ----

    private void editTouch(MotionEvent e, int action, int idx) {
        float x = e.getX(idx), y = e.getY(idx);
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (editPointer >= 0) return;
                for (int i = 0; i < 4; ++i) {
                    if (!editBar[i].contains(x, y)) continue;
                    if (i < 2 && editSel != null) {
                        editSel.fr = Math.max(0.035f, Math.min(0.22f, editSel.fr * (i == 0 ? 0.9f : 1.1f)));
                    } else if (i == 2) {
                        for (Btn b : buttons) { b.fx = b.dfx; b.fy = b.dfy; b.fr = b.dfr; }
                        editSel = null;
                    } else if (i == 3) {
                        editing = false;
                        editSel = null;
                        if (editorOnly) {
                            saveLayout();
                            activity.finish();
                            return;
                        }
                    }
                    saveLayout();
                    place();
                    return;
                }
                Btn best = null;
                float bestD = Float.MAX_VALUE;
                for (Btn b : buttons) {
                    if (b.id == COLLAPSE) continue;
                    float d = (float) Math.hypot(x - b.cx, y - b.cy);
                    if (d < Math.max(b.r * 1.2f, getHeight() * 0.06f) && d < bestD) { best = b; bestD = d; }
                }
                editSel = best;
                editDrag = best;
                if (best != null) {
                    editPointer = pid;
                    editOffX = best.cx - x;
                    editOffY = best.cy - y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (editDrag == null) return;
                for (int i = 0; i < e.getPointerCount(); ++i) {
                    if (e.getPointerId(i) != editPointer) continue;
                    int w = getWidth(), h = getHeight();
                    float cx = Math.max(editDrag.r, Math.min(w - editDrag.r, e.getX(i) + editOffX));
                    float cy = Math.max(editDrag.r, Math.min(h - editDrag.r, e.getY(i) + editOffY));
                    editDrag.fx = (editDrag.fromRight ? w - cx : cx) / (h * scale);
                    editDrag.fy = bottomAnchored(editDrag) ? 1f - (h - cy) / (h * scale) : cy / (h * scale);
                    place();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == editPointer || action == MotionEvent.ACTION_CANCEL) {
                    editPointer = -1;
                    editDrag = null;
                    saveLayout();
                }
                break;
        }
    }

    private void sendRs() {
        float dx = (rsX - rsCx) / rsR, dy = (rsY - rsCy) / rsR;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; }
        if (len < 0.25f) { dx = 0; dy = 0; }
        nativeAxis(AX_RX, dx);
        nativeAxis(AX_RY, dy);
    }

    // ---- drawing: every piece is a cached Bitmap made in place() (buildPictures); onDraw only places them ----

    @Override
    protected void onDraw(Canvas c) {
        if (!shown) return;
        if (editorOnly) c.drawColor(0xFF1B1B1F);
        if (editing) {
            fill.setColor(Color.BLACK);
            fill.setAlpha(110);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
        }
        final long now = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
        boolean animating = false;
        for (Btn b : buttons) {
            if (b.id == RS_PAD || b.id == TAUNT_PAD || b.id == COLLAPSE) continue;
            if (collapsed && b.id != START) continue;
            if (b.normal == null) continue;
            boolean down = b.pointer >= 0 || (b.id == TRIGGER + AX_LT && ltDown);
            if (!down && b.visPressed) b.visReleased = now;
            if (down && !b.visPressed) b.visReleased = 0;
            b.visPressed = down;
            // Pressed: 0.92 and the strong glow (at least 80 ms), then a spring back over 80 ms.
            float k = 1f;
            Bitmap pic = b.normal;
            long since = now - b.visReleased;
            if (down || (b.visReleased != 0 && wall - b.downTime < 80)) {  // (downTime: press(), wall clock)
                k = 0.92f;
                pic = b.pressed;
                animating = !down;
            } else if (b.visReleased != 0 && since < 80) {
                k = 0.92f + 0.08f * since / 80f;
                animating = true;
            }
            drawCentered(c, pic, b.cx, b.cy, k);
            if (editing && b == editSel) {
                ring.setAlpha(255);
                c.drawCircle(b.cx, b.cy, b.r * 1.12f, ring);
            }
        }
        if (!editing && !editorOnly) {
            // HIDE / SHOW (top right): tap to hide or show the buttons, hold to edit the layout. Never fainter than 85 %.
            int a = blit.getAlpha();
            blit.setAlpha(Math.max(a, collapsePointer >= 0 ? 255 : 217));
            Bitmap t = collapsed ? showTag : hideTag;
            if (t != null) c.drawBitmap(t, hideRect.right - t.getWidth() + tagPad(), hideRect.top - tagPad(), blit);
            blit.setAlpha(a);
        }
        if (animating) postInvalidateOnAnimation();
        if (collapsed) return;
        // TAUNT cross pad.
        Btn pad = padBtn();
        if (padBmp != null) {
            drawCentered(c, padBmp, pad.cx, pad.cy, 1f);
            if (padDir >= 0) {
                RectF arm = padArms[padDir];
                c.save();
                c.translate(pad.cx, pad.cy);
                c.drawRoundRect(arm, pad.r * 0.08f, pad.r * 0.08f, armLit);
                c.restore();
            }
            if (padTag != null) c.drawBitmap(padTag, pad.cx - padTag.getWidth() / 2f, pad.cy + pad.r + 6 * dp, blit);
            if (editing && pad == editSel) {
                ring.setAlpha(255);
                c.drawCircle(pad.cx, pad.cy, pad.r * 1.2f, ring);
            }
        }
        // Right stick.
        if (rsBase != null) {
            drawCentered(c, rsBase, rsCx, rsCy, 1f);
            float kx = rsCx, ky = rsCy;
            if (rsPointer >= 0) {
                float dx = rsX - rsCx, dy = rsY - rsCy, len = (float) Math.hypot(dx, dy);
                if (len > rsR) { dx *= rsR / len; dy *= rsR / len; }
                kx += dx; ky += dy;
            }
            drawCentered(c, rsThumb, kx, ky, 1f);
            if (rsTag != null) c.drawBitmap(rsTag, rsCx - rsTag.getWidth() / 2f, Math.min(getHeight() - rsTag.getHeight(),
                    rsCy + rsR + 4 * dp), blit);
            if (editing && rsBtn() == editSel) {
                ring.setAlpha(255);
                c.drawCircle(rsCx, rsCy, rsR * 1.15f, ring);
            }
        }
        // Movement stick: at its home while free, where the thumb landed while held.
        if (stickBase != null) {
            float bx = stickPointer >= 0 ? stickBaseX : stickHomeX;
            float by = stickPointer >= 0 ? stickBaseY : stickHomeY;
            drawCentered(c, stickBase, bx, by, 1f);
            float sx = bx, sy = by;
            if (stickPointer >= 0) {
                float dx = stickX - bx, dy = stickY - by, len = (float) Math.hypot(dx, dy);
                float max = stickR * 1.25f;
                if (len > max) { dx *= max / len; dy *= max / len; }
                sx += dx; sy += dy;
            }
            drawCentered(c, running ? stickThumbRun : stickThumb, sx, sy, 1f);
        }
        if (editing) {
            String[] labels = {"−  SMALLER", "+  BIGGER", "RESET", "DONE"};
            text.setAlpha(255);
            text.setTextSize(getHeight() * 0.032f);
            for (int i = 0; i < 4; ++i) {
                boolean on = i >= 2 || editSel != null;
                fill.setColor(i == 3 ? GREEN : 0xFF5A5A5A);
                fill.setAlpha(on ? 230 : 90);
                c.drawRoundRect(editBar[i], 16, 16, fill);
                c.drawText(labels[i], editBar[i].centerX(), editBar[i].centerY() + text.getTextSize() * 0.35f, text);
            }
            small.setAlpha(255);
            small.setTextSize(getHeight() * 0.03f);
            c.drawText("Drag a button to move it. Tap one, then − / + to resize. DONE saves.", getWidth() / 2f, editBar[0].top - getHeight() * 0.03f, small);
        }
    }

    private void drawCentered(Canvas c, Bitmap b, float cx, float cy, float k) {
        if (b == null) return;
        if (k == 1f) {
            c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, blit);
            return;
        }
        c.save();
        c.scale(k, k, cx, cy);
        c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, blit);
        c.restore();
    }

    // ---- the cached pictures ----

    private static final int DISC_TOP = 0xFF2A2A2E, DISC_BOTTOM = 0xFF111113, TAG_RED = 0xFFB0081C, INK = 0xFF0B0B0C;
    private int builtHeight;

    private Typeface heavyItalic() {
        Typeface t = Typeface.create("sans-serif-black", Typeface.ITALIC);
        return t != null ? t : Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
    }

    private float tagPad() { return 10 * dp; }

    /** Makes every picture that is missing or no longer matches its size. Runs from place(), never from onDraw. */
    private void buildPictures(int h) {
        boolean all = builtHeight != h;
        builtHeight = h;
        for (Btn b : buttons) {
            if (b.id == COLLAPSE || b.id == RS_PAD || b.id == TAUNT_PAD) continue;
            int key = Math.round(b.r);
            if (!all && b.normal != null && b.builtFor == key) continue;
            if (b.normal != null) { b.normal.recycle(); b.pressed.recycle(); }
            b.normal = disc(b, false);
            b.pressed = disc(b, true);
            b.builtFor = key;
        }
        Btn pad = padBtn(), rs = rsBtn();
        int padKey = Math.round(pad.r), rsKey = Math.round(rs.r);
        if (all || padBmp == null || pad.builtFor != padKey) {
            recycle(padBmp);
            padBmp = crossPad(pad.r);
            pad.builtFor = padKey;
        }
        if (all || rsBase == null || rs.builtFor != rsKey) {
            recycle(rsBase); recycle(rsThumb);
            rsBase = stickBaseBitmap(rs.r);
            rsThumb = thumbBitmap(rs.r * 0.4f, false);
            rs.builtFor = rsKey;
        }
        if (all || stickBase == null || stickBase.getWidth() != stickSide(stickR * 1.25f)) {
            recycle(stickBase); recycle(stickThumb); recycle(stickThumbRun);
            float base = stickR * 1.25f;
            stickBase = stickBaseBitmap(base);
            stickThumb = thumbBitmap(base * 0.4f, false);
            stickThumbRun = thumbBitmap(base * 0.4f, true);
        }
        if (all || padTag == null) {
            recycle(padTag); recycle(rsTag); recycle(hideTag); recycle(showTag);
            float th = Math.max(22 * dp, 0.042f * h);
            padTag = tag("TAUNT", th, false, false);
            rsTag = tag("MOVE OPP · TAP = STORE", th, false, false);
            hideTag = tag("HIDE", hideRect.height(), true, true);
            showTag = tag("SHOW", hideRect.height(), true, false);
        }
    }

    private static void recycle(Bitmap b) { if (b != null) b.recycle(); }

    private int discSide(float r) { return (int) Math.ceil(2 * r + 2 * 44 * dp); }

    /** An action button: shadow, coloured glow, gradient disc, 3 dp ring, the action name and its coloured tag. */
    private Bitmap disc(Btn b, boolean pressedLook) {
        float r = Math.max(4, b.r);
        int side = discSide(r);
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float c = side / 2f, d = 2 * r;
        boolean small = b.sub.isEmpty();  // BACK / PAUSE
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // drop shadow (0, 6 dp, 18 dp blur, 60 % black)
        p.setColor(0x99000000);
        p.setMaskFilter(new BlurMaskFilter(9 * dp, BlurMaskFilter.Blur.NORMAL));
        cv.drawCircle(c, c + 6 * dp, r, p);
        // outer glow in the ring colour (35 %, 60 % pressed)
        if (!small || pressedLook) {
            p.setMaskFilter(new BlurMaskFilter(14 * dp, BlurMaskFilter.Blur.NORMAL));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(6 * dp);
            p.setColor((b.color & 0x00FFFFFF) | ((pressedLook ? 153 : 89) << 24));
            cv.drawCircle(c, c, r, p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setMaskFilter(null);
        // disc
        p.setShader(new RadialGradient(c, c - r, 2 * r, new int[] {DISC_TOP, DISC_BOTTOM}, new float[] {0f, 0.7f},
                Shader.TileMode.CLAMP));
        cv.drawCircle(c, c, r, p);
        p.setShader(null);
        // ring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((small ? 2 : 3) * dp);
        p.setColor(b.color);
        cv.drawCircle(c, c, r - p.getStrokeWidth() / 2f, p);
        p.setStyle(Paint.Style.FILL);
        // the action name
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(heavyItalic());
        float size = d * (small ? 0.24f : b.label.length() > 6 ? 0.17f : 0.20f);
        t.setTextSize(size);
        float maxW = d * (small ? 0.80f : 0.84f);
        if (t.measureText(b.label) > maxW) t.setTextScaleX(maxW / t.measureText(b.label));
        if (small) {
            cv.drawText(b.label, c, c + size * 0.36f, t);
            return bmp;
        }
        float tagH = d * 0.15f;
        float nameY = c - d * 0.02f;
        cv.drawText(b.label, c, nameY, t);
        // the pad-button tag
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        st.setTextSize(d * 0.10f);
        st.setTextAlign(Paint.Align.CENTER);
        st.setColor(INK);
        float tw = st.measureText(b.sub) + d * 0.12f, top = nameY + d * 0.07f;
        p.setColor(b.color);
        cv.drawRoundRect(new RectF(c - tw / 2f, top, c + tw / 2f, top + tagH), 3 * dp, 3 * dp, p);
        Paint.FontMetrics fm = st.getFontMetrics();
        cv.drawText(b.sub, c, top + tagH / 2f - (fm.ascent + fm.descent) / 2f, st);
        return bmp;
    }

    private int stickSide(float r) { return (int) Math.ceil(2 * r + 2 * 20 * dp); }

    /** A stick's base: 2 dp red ring (65 %), dark red gradient (55 %), a dashed inner ring at 12 % inset. */
    private Bitmap stickBaseBitmap(float r) {
        int side = stickSide(r);
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float c = side / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(c, c, r, 0x8C280006, 0x8C0A0002, Shader.TileMode.CLAMP));
        cv.drawCircle(c, c, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * dp);
        p.setColor(0xA6F0162F);
        cv.drawCircle(c, c, r - dp, p);
        p.setStrokeWidth(dp);
        p.setColor(0x2EFFFFFF);
        p.setPathEffect(new DashPathEffect(new float[] {5 * dp, 4 * dp}, 0));
        cv.drawCircle(c, c, r * 0.88f, p);
        return bmp;
    }

    /** A stick's thumb: white to light grey, 3 dp red ring (orange while running), a drop shadow. */
    private Bitmap thumbBitmap(float r, boolean runningLook) {
        int side = (int) Math.ceil(2 * r + 2 * 16 * dp);
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float c = side / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x99000000);
        p.setMaskFilter(new BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL));
        cv.drawCircle(c, c + 4 * dp, r, p);
        p.setMaskFilter(null);
        p.setShader(new RadialGradient(c - r * 0.25f, c - r * 0.3f, r * 1.3f, Color.WHITE, 0xFFBDBDC2, Shader.TileMode.CLAMP));
        cv.drawCircle(c, c, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * dp);
        p.setColor(runningLook ? ORANGE : BRAND);
        cv.drawCircle(c, c, r - 1.5f * dp, p);
        return bmp;
    }

    /** The TAUNT pad: one plus shape (arms a third of its size), dark fill, glowing red outline, centre circle,
     *  white arrows. Also sets padArms (the lit arm of a held direction), relative to the pad's centre. */
    private Bitmap crossPad(float r) {
        float s = 2 * r, arm = s / 3f, half = arm / 2f;
        int side = (int) Math.ceil(s + 2 * 20 * dp);
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float c = side / 2f;
        Path plus = new Path();
        plus.moveTo(c - half, c - r); plus.lineTo(c + half, c - r); plus.lineTo(c + half, c - half);
        plus.lineTo(c + r, c - half); plus.lineTo(c + r, c + half); plus.lineTo(c + half, c + half);
        plus.lineTo(c + half, c + r); plus.lineTo(c - half, c + r); plus.lineTo(c - half, c + half);
        plus.lineTo(c - r, c + half); plus.lineTo(c - r, c - half); plus.lineTo(c - half, c - half);
        plus.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(new android.graphics.CornerPathEffect(3 * dp));
        p.setColor(0xBF140205);
        cv.drawPath(plus, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5 * dp);
        p.setColor(0x80F0162F);
        p.setMaskFilter(new BlurMaskFilter(8 * dp, BlurMaskFilter.Blur.NORMAL));
        cv.drawPath(plus, p);
        p.setMaskFilter(null);
        p.setStrokeWidth(2.2f * dp);
        p.setColor(BRAND);
        cv.drawPath(plus, p);
        p.setPathEffect(null);
        p.setStrokeWidth(1.5f * dp);
        cv.drawCircle(c, c, s * 0.06f, p);
        // arrows
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        float a = arm * 0.28f, o = r - arm * 0.5f;
        float[][] tips = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};  // up, left, right, down
        for (float[] t : tips) {
            float tx = c + t[0] * o, ty = c + t[1] * o;
            Path tri = new Path();
            tri.moveTo(tx + t[0] * a * 0.6f, ty + t[1] * a * 0.6f);
            tri.lineTo(tx - t[0] * a * 0.5f + t[1] * a * 0.7f, ty - t[1] * a * 0.5f + t[0] * a * 0.7f);
            tri.lineTo(tx - t[0] * a * 0.5f - t[1] * a * 0.7f, ty - t[1] * a * 0.5f - t[0] * a * 0.7f);
            tri.close();
            cv.drawPath(tri, p);
        }
        padArms[0].set(-half, -r, half, -half);
        padArms[1].set(-r, -half, -half, half);
        padArms[2].set(half, -half, r, half);
        padArms[3].set(-half, half, half, r);
        return bmp;
    }

    /** A red slanted tag (skewed -12 degrees); HIDE / SHOW: gradient and a white stripe on the right edge. */
    private Bitmap tag(String label, float h, boolean hide, boolean hideArrow) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTypeface(heavyItalic());
        t.setTextSize(h * 0.55f);
        t.setLetterSpacing(0.05f);
        float slant = (float) (h * Math.tan(Math.toRadians(12)));
        float arrowW = hide ? h * 0.42f : 0f;
        float textW = t.measureText(label);
        float w = textW + arrowW + h * 0.9f + slant;
        float pad = tagPad();
        Bitmap bmp = Bitmap.createBitmap((int) Math.ceil(w + 2 * pad), (int) Math.ceil(h + 2 * pad), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Path shape = new Path();
        shape.moveTo(pad + slant, pad); shape.lineTo(pad + w, pad); shape.lineTo(pad + w - slant, pad + h);
        shape.lineTo(pad, pad + h); shape.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x99000000);
        p.setMaskFilter(new BlurMaskFilter(5 * dp, BlurMaskFilter.Blur.NORMAL));
        cv.save();
        cv.translate(0, 3 * dp);
        cv.drawPath(shape, p);
        cv.restore();
        p.setMaskFilter(null);
        if (hide) p.setShader(new LinearGradient(pad, 0, pad + w, 0, 0xFFF0162F, 0xFF8E0618, Shader.TileMode.CLAMP));
        else p.setColor(TAG_RED);
        cv.drawPath(shape, p);
        p.setShader(null);
        if (hide) {  // the white stripe along the right edge
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * dp);
            p.setColor(Color.WHITE);
            cv.drawLine(pad + w + 2 * dp, pad, pad + w - slant + 2 * dp, pad + h, p);
            p.setStyle(Paint.Style.FILL);
        }
        Paint.FontMetrics fm = t.getFontMetrics();
        float tx = pad + slant + h * 0.35f, ty = pad + h / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(label, tx, ty, t);
        if (hide) {  // a small triangle: up for HIDE, down for SHOW
            float ax = tx + textW + h * 0.12f, s2 = h * 0.30f, cy = pad + h / 2f;
            Path tri = new Path();
            if (hideArrow) { tri.moveTo(ax, cy + s2 / 2); tri.lineTo(ax + s2, cy + s2 / 2); tri.lineTo(ax + s2, cy - s2 / 2); }
            else { tri.moveTo(ax, cy - s2 / 2); tri.lineTo(ax + s2, cy - s2 / 2); tri.lineTo(ax + s2 / 2, cy + s2 / 2); }
            tri.close();
            p.setColor(Color.WHITE);
            cv.drawPath(tri, p);
        }
        return bmp;
    }
}
