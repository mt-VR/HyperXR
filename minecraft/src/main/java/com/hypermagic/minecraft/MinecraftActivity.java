package com.hypermagic.minecraft;

import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import org.lwjgl.glfw.CallbackBridge;

import pojlib.API;
import pojlib.InstanceHandler;
import pojlib.account.MinecraftAccount;
import pojlib.input.AWTInputBridge;
import pojlib.input.EfficientAndroidLWJGLKeycode;
import pojlib.input.LwjglGlfwKeycode;
import pojlib.util.Constants;
import pojlib.util.Logger;
import pojlib.util.json.MinecraftInstances;

/**
 * Runs Minecraft Java Edition inside this activity's process (":minecraft"), the way QuestCraft's
 * UnityPlayerActivity does, without Unity: the JVM starts on a worker thread, Vivecraft opens an
 * OpenXR session on this activity and HyperMagic's OpenXR runtime draws the game in the headset.
 * When the game quits the JVM ends the process, so it never takes HyperMagic down with it.
 */
public class MinecraftActivity extends Activity {
    public static final String EXTRA_INSTANCE = "instance";
    public static final String EXTRA_ACCOUNT = "account";

    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    private static volatile boolean started;
    private TextView status;

    /** Opens the game; the instance must be installed (McProfiles.prepare). */
    public static void start(Context ctx, String instanceName, String accountUuid) {
        ctx.startActivity(new Intent(ctx, MinecraftActivity.class)
                .putExtra(EXTRA_INSTANCE, instanceName)
                .putExtra(EXTRA_ACCOUNT, accountUuid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        Constants.initConstants(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(18f);
        status.setText("Iniciando o Minecraft...");
        setContentView(status);
        GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        updateWindowSize();
        CallbackBridge.nativeSetUseInputStackQueue(true);

        // one JVM per process: a recreated activity (rotation, return from background) keeps it
        if (started) return;
        started = true;
        String name = getIntent().getStringExtra(EXTRA_INSTANCE);
        String uuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        new Thread(() -> runGame(name, uuid), "minecraft-main").start();
    }

    private void runGame(String name, String uuid) {
        try {
            MinecraftAccount account = McProfiles.account(this, uuid);
            if (account == null) { fail("Entre na conta da Microsoft no HyperMagic (Minecraft > Conta) e tente de novo."); return; }
            MinecraftInstances instances = API.loadAll();
            MinecraftInstances.Instance instance = instances.load(name);
            if (instance == null || instance.classpath == null) { fail("O perfil " + name + " ainda não foi instalado."); return; }
            setStatus("Conferindo arquivos e mods...");
            McRuntime.installAll(this, (what, f) -> setStatus(what));
            if (!API.prelaunch(this, instances, instance)) { fail("Não deu para preparar o Java. Veja o registro em Android/data/com.hypermagic/files/minecraft/latestlog.txt"); return; }
            setStatus("Abrindo o Minecraft. Coloque o celular no óculos.");
            InstanceHandler.launchInstance(this, account, instance);
            // the JVM normally ends the process itself; if it returns, close cleanly
            endGame();
        } catch (Throwable t) {
            Logger.getInstance().appendToLog("HyperMagic launch failed: " + t);
            fail("Erro ao abrir: " + t.getMessage());
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void fail(String text) {
        runOnUiThread(() -> {
            status.setText(text + "\n\nToque para voltar.");
            status.setOnClickListener(v -> endGame());
        });
    }

    /** Called when the game asks to restart the launcher (CallbackBridge.restartUnitySession) or quits. */
    public void endGame() {
        runOnUiThread(() -> {
            finishAndRemoveTask();
            Process.killProcess(Process.myPid());
        });
    }

    private void updateWindowSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        CallbackBridge.physicalWidth = dm.widthPixels;
        CallbackBridge.physicalHeight = dm.heightPixels;
    }

    // ------------------------------------------------------------------ clipboard (awt_bridge.c)

    @SuppressWarnings("unused")
    public static void querySystemClipboard() {
        ClipData clip = GLOBAL_CLIPBOARD == null ? null : GLOBAL_CLIPBOARD.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        AWTInputBridge.nativeClipboardReceived(text == null ? null : text.toString(), text == null ? null : "plain");
    }

    @SuppressWarnings("unused")
    public static void putClipboardData(String data, String mimeType) {
        if (GLOBAL_CLIPBOARD == null) return;
        if ("text/plain".equals(mimeType)) GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("AWT Paste", data));
        else if ("text/html".equals(mimeType)) GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newHtmlText("AWT Paste", data, data));
    }

    // ------------------------------------------------------------------ keyboard and mouse (Pojlib)

    private static boolean isGamepad(InputDevice d) {
        if (d == null) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // controllers reach the game through OpenXR (the runtime reads them), not as a mouse
        if (isGamepad(event.getDevice())) return super.dispatchGenericMotionEvent(event);
        int mouse = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            int tool = event.getToolType(i);
            if (tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS) { mouse = i; break; }
        }
        if (mouse == -1) return super.dispatchGenericMotionEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                CallbackBridge.sendCursorPos(event.getX(mouse) * 100, event.getY(mouse) * 100);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(), true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(), false);
            default:
                return super.dispatchGenericMotionEvent(event);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isGamepad(event.getDevice()) && event.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (processKeyEvent(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // back opens the pause menu, as on QuestCraft
            if (event.getAction() == KeyEvent.ACTION_UP) sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean processKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_UNKNOWN) return true;
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if (event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_MULTIPLE) return true;
        if (action == KeyEvent.ACTION_UP && (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;
        InputDevice d = event.getDevice();
        if (d != null && ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) && code == KeyEvent.KEYCODE_BACK) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, action == KeyEvent.ACTION_DOWN);
            return true;
        }
        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(code);
        if (EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    private static boolean sendMouseButtonUnconverted(int button, boolean down) {
        int glfw;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY: glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT; break;
            case MotionEvent.BUTTON_TERTIARY: glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE; break;
            case MotionEvent.BUTTON_SECONDARY: glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT; break;
            default: return false;
        }
        sendMouseButton(glfw, down);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // the JVM cannot be restarted in the same process
        if (isFinishing()) Process.killProcess(Process.myPid());
    }
}
