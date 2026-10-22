package com.hyperxr.openxr;

import android.app.Activity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Full-screen surface the HyperXR OpenXR runtime composites into, added on top of the OpenXR
 * application's activity. The runtime library runs inside the application's process and loads
 * this class from the HyperXR APK, so it is plain Java with no dependency on Kotlin or AndroidX.
 */
public final class RuntimeSurface implements SurfaceHolder.Callback {
    // ------------------------------------------------------------------ controller input
    // Read by the runtime (platform_android.cpp readInput) straight from this direct buffer:
    // int buttons @0, float stick lx @4, ly @8, rx @12, ry @16, trigger l @20, r @24,
    // squeeze l @28, r @32, int screen touch @36. Bits match HX_BTN_* in hx_runtime.h.
    private static final ByteBuffer INPUT = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static final int BTN_A = 1, BTN_B = 2, BTN_X = 4, BTN_Y = 8, BTN_MENU = 16, BTN_SYSTEM = 32, BTN_THUMB_L = 64, BTN_THUMB_R = 128;
    private static int buttons;

    public static ByteBuffer inputBuffer() { return INPUT; }

    /** Records a gamepad / Joy-Con key. Returns true when it is a controller key. */
    static boolean onKey(KeyEvent e) {
        int bit = 0;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: bit = BTN_A; break;
            case KeyEvent.KEYCODE_BUTTON_B: bit = BTN_B; break;
            case KeyEvent.KEYCODE_BUTTON_X: bit = BTN_X; break;
            case KeyEvent.KEYCODE_BUTTON_Y: bit = BTN_Y; break;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MENU: bit = BTN_MENU; break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BUTTON_MODE: bit = BTN_SYSTEM; break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: bit = BTN_THUMB_L; break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: bit = BTN_THUMB_R; break;
            case KeyEvent.KEYCODE_BUTTON_L1: INPUT.putFloat(28, down ? 1f : 0f); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: INPUT.putFloat(32, down ? 1f : 0f); return true;
            case KeyEvent.KEYCODE_BUTTON_L2: INPUT.putFloat(20, down ? 1f : 0f); return true;
            case KeyEvent.KEYCODE_BUTTON_R2: INPUT.putFloat(24, down ? 1f : 0f); return true;
            default: return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) buttons |= bit;
        else if (e.getAction() == KeyEvent.ACTION_UP) buttons &= ~bit;
        INPUT.putInt(0, buttons);
        return true;
    }

    private static float dead(float v) { return Math.abs(v) < 0.12f ? 0f : v; }

    /** Records sticks and analog triggers. A lone right Joy-Con drives the right stick. */
    static boolean onMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        InputDevice d = e.getDevice();
        String name = d != null ? d.getName() : "";
        boolean twoSticks = d != null && d.getMotionRange(MotionEvent.AXIS_RZ) != null;
        boolean rightOnly = !twoSticks && name != null && (name.contains("(R)") || name.contains("Right"));
        float x = dead(e.getAxisValue(MotionEvent.AXIS_X)), y = -dead(e.getAxisValue(MotionEvent.AXIS_Y));
        INPUT.putFloat(rightOnly ? 12 : 4, x);
        INPUT.putFloat(rightOnly ? 16 : 8, y);
        if (twoSticks) {
            INPUT.putFloat(12, dead(e.getAxisValue(MotionEvent.AXIS_Z)));
            INPUT.putFloat(16, -dead(e.getAxisValue(MotionEvent.AXIS_RZ)));
        }
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        if (lt > 0f || INPUT.getFloat(20) > 0f) INPUT.putFloat(20, lt);
        if (rt > 0f || INPUT.getFloat(24) > 0f) INPUT.putFloat(24, rt);
        return true;
    }

    /** The screen touch (the VR Box button presses the screen) is the right trigger / select. */
    static void onTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) INPUT.putInt(36, 1);
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) INPUT.putInt(36, 0);
    }

    private final Activity activity;
    private Window.Callback originalCallback;
    private volatile Surface surface;
    private volatile int width;
    private volatile int height;
    private SurfaceView view;

    private RuntimeSurface(Activity activity) {
        this.activity = activity;
    }

    /** Called by the runtime (any thread). The surface appears asynchronously. */
    public static RuntimeSurface create(Activity activity) {
        final RuntimeSurface r = new RuntimeSurface(activity);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { r.attach(); }
        });
        return r;
    }

    /**
     * The surface lives in its own window above the activity (NativeActivity apps hand their own
     * window surface to native code, so a view inside it would not be shown).
     *
     * Input: controllers belong to the runtime in OpenXR, so the runtime watches them. For normal
     * activities (Unity, GameActivity...) the window callback is wrapped: every event is recorded
     * and still delivered to the app. A NativeActivity reads its input queue directly, bypassing
     * that callback, so there this window takes focus and touches instead.
     */
    private void attach() {
        boolean nativeActivity = activity instanceof android.app.NativeActivity;
        view = new SurfaceView(activity);
        view.getHolder().addCallback(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                (nativeActivity ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.OPAQUE);
        lp.token = activity.getWindow().getAttributes().token;
        lp.setTitle("HyperXR Runtime");
        if (nativeActivity) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent e) { return RuntimeSurface.onKey(e); }
            });
            view.setOnGenericMotionListener(new View.OnGenericMotionListener() {
                @Override public boolean onGenericMotion(View v, MotionEvent e) { return RuntimeSurface.onMotion(e); }
            });
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { RuntimeSurface.onTouch(e); return true; }
            });
            view.requestFocus();
        } else wrapCallback();
        try {
            activity.getWindowManager().addView(view, lp);
        } catch (Throwable t) {
            // no window token yet (activity not attached): fall back to a view inside the activity
            view.setZOrderOnTop(true);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void wrapCallback() {
        final Window w = activity.getWindow();
        final Window.Callback orig = w.getCallback();
        if (orig == null || Proxy.isProxyClass(orig.getClass())) return;
        originalCallback = orig;
        Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                        String n = m.getName();
                        if (args != null && args.length == 1) {
                            if (n.equals("dispatchKeyEvent")) onKey((KeyEvent) args[0]);
                            else if (n.equals("dispatchGenericMotionEvent")) onMotion((MotionEvent) args[0]);
                            else if (n.equals("dispatchTouchEvent")) onTouch((MotionEvent) args[0]);
                        }
                        try {
                            return m.invoke(orig, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
        w.setCallback(proxy);
    }

    public Surface getSurface() { return surface; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** Display rotation of the activity (Surface.ROTATION_*), for the head tracker axis mapping. */
    public static int displayRotation(Activity activity) {
        try {
            return activity.getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_90;
        }
    }

    public static float refreshRate(Activity activity) {
        try {
            return activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        } catch (Throwable t) {
            return 60f;
        }
    }

    public void destroy() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (originalCallback != null) { activity.getWindow().setCallback(originalCallback); originalCallback = null; }
                if (view == null) return;
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                else try { activity.getWindowManager().removeView(view); } catch (Throwable ignored) { }
                view = null;
            }
        });
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surface = holder.getSurface(); }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        width = w;
        height = h;
        surface = holder.getSurface();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { surface = null; }
}
