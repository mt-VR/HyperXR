package pojlib.account;

import android.app.Activity;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import pojlib.API;
import pojlib.util.FileUtil;
import pojlib.util.GsonUtils;
import pojlib.util.Logger;

/**
 * Microsoft sign-in for Minecraft, replacing Pojlib's msal4j version (a desktop library) with the
 * OAuth device code flow it used: the player opens microsoft.com/link on any device and types the
 * code shown in HyperMagic. The Microsoft token then goes through {@link Msa} (Xbox Live, XSTS,
 * Minecraft services) exactly as in Pojlib. The refresh token stays in the app's private storage.
 */
public class LoginHelper {
    /** QuestCraft's Azure application, the one Pojlib signs in with (approved for the Minecraft services). */
    public static final String CLIENT_ID = "d17a73a2-707c-40f5-8c90-d3eda0956f10";
    private static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String SCOPES = "XboxLive.SignIn XboxLive.offline_access";

    /** State of the sign-in in progress, read by the launcher UI. */
    public static volatile String userCode;
    public static volatile String verificationUri;
    public static volatile String error;
    public static volatile boolean busy;
    private static volatile Thread loginThread;

    public static void login(Activity activity) {
        if (busy) return;
        busy = true;
        userCode = null; verificationUri = null; error = null;
        loginThread = new Thread(() -> {
            try {
                JSONObject code = post(AUTHORITY + "/devicecode", "client_id", CLIENT_ID, "scope", SCOPES);
                userCode = code.getString("user_code");
                verificationUri = code.optString("verification_uri", "https://www.microsoft.com/link");
                API.msaMessage = code.optString("message");
                long interval = Math.max(2, code.optLong("interval", 5)) * 1000L;
                long deadline = System.currentTimeMillis() + code.optLong("expires_in", 900) * 1000L;
                String device = code.getString("device_code");
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval);
                    JSONObject token = post(AUTHORITY + "/token",
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", CLIENT_ID, "device_code", device);
                    String err = token.optString("error", "");
                    if (err.equals("authorization_pending")) continue;
                    if (err.equals("slow_down")) { interval += 5000; continue; }
                    if (!err.isEmpty()) throw new IOException(token.optString("error_description", err));
                    finish(activity, token);
                    return;
                }
                throw new IOException("O código expirou. Tente de novo.");
            } catch (Exception e) {
                Logger.getInstance().appendToLog("MicrosoftLogin | " + e);
                error = e.getMessage();
                API.msaMessage = "MicrosoftLogin | " + e.getMessage();
            } finally {
                userCode = null;
                busy = false;
            }
        }, "mc-login");
        loginThread.start();
    }

    /** Silent sign-in with the stored refresh token. Returns null when it has to be done again. */
    public static MinecraftAccount refreshAccount(Activity activity, String uuid) {
        try {
            File refresh = refreshFile(activity, uuid);
            if (!refresh.exists()) return null;
            JSONObject token = post(AUTHORITY + "/token", "grant_type", "refresh_token", "client_id", CLIENT_ID,
                    "refresh_token", FileUtil.read(refresh.getAbsolutePath()).trim(), "scope", SCOPES);
            if (token.has("error")) throw new IOException(token.optString("error_description", token.getString("error")));
            return finish(activity, token);
        } catch (Exception ex) {
            Logger.getInstance().appendToLog("Couldn't refresh token! " + ex);
            return null;
        }
    }

    private static MinecraftAccount finish(Activity activity, JSONObject token) throws Exception {
        MinecraftAccount acc = new Msa(activity).performLogin(token.getString("access_token"));
        if (acc == null) throw new IOException("A conta Xbox não respondeu.");
        File accounts = new File(activity.getFilesDir(), "accounts");
        accounts.mkdirs();
        GsonUtils.objectToJsonFile(new File(accounts, acc.uuid + ".json").getAbsolutePath(), acc);
        String refreshToken = token.optString("refresh_token", "");
        if (!refreshToken.isEmpty()) FileUtil.write(refreshFile(activity, acc.uuid).getAbsolutePath(), refreshToken.getBytes(StandardCharsets.UTF_8));
        API.currentAcc = acc;
        API.profileName = acc.username;
        API.profileUUID = acc.uuid;
        API.isDemoMode = acc.isDemoMode;
        API.profileImage = MinecraftAccount.getSkinFaceUrl(acc);
        return acc;
    }

    static File refreshFile(Activity activity, String uuid) {
        return new File(new File(activity.getFilesDir(), "accounts"), uuid + ".refresh");
    }

    private static JSONObject post(String url, String... form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < form.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(form[i], "UTF-8")).append('=').append(URLEncoder.encode(form[i + 1], "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        // OAuth errors (authorization_pending...) come back as 400 with a JSON body
        try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) throw new IOException("HTTP " + status);
            return new JSONObject(FileUtil.read(in));
        } finally {
            c.disconnect();
        }
    }
}
