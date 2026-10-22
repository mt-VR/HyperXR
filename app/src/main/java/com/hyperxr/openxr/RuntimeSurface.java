package com.hyperxr.openxr;

import android.app.Activity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * Full-screen surface the HyperXR OpenXR runtime composites into, added on top of the OpenXR
 * application's activity. The runtime library runs inside the application's process and loads
 * this class from the HyperXR APK, so it is plain Java with no dependency on Kotlin or AndroidX.
 */
public final class RuntimeSurface implements SurfaceHolder.Callback {
    private final Activity activity;
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
     * window surface to native code, so a view inside it would not be shown). The window never
     * takes focus or touches: input keeps going to the application.
     */
    private void attach() {
        view = new SurfaceView(activity);
        view.getHolder().addCallback(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.OPAQUE);
        lp.token = activity.getWindow().getAttributes().token;
        lp.setTitle("HyperXR Runtime");
        try {
            activity.getWindowManager().addView(view, lp);
        } catch (Throwable t) {
            // no window token yet (activity not attached): fall back to a view inside the activity
            view.setZOrderOnTop(true);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
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
