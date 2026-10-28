package com.hypermagic.minecraft;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import pojlib.API;
import pojlib.InstanceHandler;
import pojlib.account.LoginHelper;
import pojlib.account.MinecraftAccount;
import pojlib.util.Constants;
import pojlib.util.FileUtil;
import pojlib.util.Logger;
import pojlib.util.json.MinecraftInstances;

/**
 * The two Minecraft profiles of HyperMagic, both real Minecraft Java Edition with QuestCraft's
 * Vivecraft build and performance mods:
 * <ul>
 *   <li>QuestCraft: the game as QuestCraft ships it.</li>
 *   <li>GorillaCraft: the same plus ViveMonkeCraft (MIT, laggyboi20-jpg), Gorilla Tag locomotion,
 *   set up for a VR Box: no legs on the body, no hoverboard or teleport, throws capped so the player
 *   does not fly off, and more tolerance for the shake of camera hand tracking.</li>
 * </ul>
 */
public final class McProfiles {
    private McProfiles() {}

    public enum Profile {
        QUESTCRAFT("QuestCraft"), GORILLACRAFT("GorillaCraft");
        public final String instanceName;
        Profile(String name) { this.instanceName = name; }
    }

    public interface Progress { void step(String what); }

    /** Version picked when the player has not chosen one: the newest QuestCraft supports. */
    public static final String DEFAULT_VERSION = "1.21.11";
    /** ViveMonkeCraft is published for 1.21.4 and later. */
    public static final String[] GORILLA_VERSIONS = {"1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4"};
    /** QuestCraft 6.0.1's supportedVersions.json. */
    public static final String[] VERSIONS = {"1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4", "1.21.1", "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.19.2"};
    /** Bump when the GorillaCraft tuning below changes, so phones get it once. */
    private static final int GORILLA_TUNING = 1;

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("minecraft", Context.MODE_PRIVATE); }

    public static String accountUuid(Context ctx) { return prefs(ctx).getString("account", null); }
    public static String accountName(Context ctx) { return prefs(ctx).getString("accountName", null); }
    public static String version(Context ctx, Profile p) { return prefs(ctx).getString("version." + p.name(), DEFAULT_VERSION); }
    public static void setVersion(Context ctx, Profile p, String v) { prefs(ctx).edit().putString("version." + p.name(), v).apply(); }

    // ------------------------------------------------------------------ account

    /** Starts the Microsoft sign-in; progress is in LoginHelper.userCode / error / busy. */
    public static void beginLogin(Activity activity) {
        Constants.initConstants(activity);
        LoginHelper.login(activity);
    }

    /** Called by the UI while LoginHelper.busy turns false: remembers the account that signed in. */
    public static boolean saveSignedIn(Context ctx) {
        MinecraftAccount acc = API.currentAcc;
        if (acc == null) return false;
        prefs(ctx).edit().putString("account", acc.uuid).putString("accountName", acc.isDemoMode ? "Demo" : acc.username).apply();
        return true;
    }

    public static void signOut(Activity activity) {
        String uuid = accountUuid(activity);
        if (uuid != null) {
            new File(activity.getFilesDir(), "accounts/" + uuid + ".json").delete();
            new File(activity.getFilesDir(), "accounts/" + uuid + ".refresh").delete();
        }
        API.currentAcc = null;
        prefs(activity).edit().remove("account").remove("accountName").apply();
    }

    /** The saved account, refreshed when its Minecraft token expired; null when sign-in is needed. */
    public static MinecraftAccount account(Activity activity, String uuid) {
        if (uuid == null) return null;
        Constants.initConstants(activity);
        MinecraftAccount acc = MinecraftAccount.load(activity.getFilesDir() + "/accounts", uuid);
        if (acc == null) return null;
        if (acc.isDemoMode || acc.expiresOn > System.currentTimeMillis() + 60_000) { API.currentAcc = acc; return acc; }
        MinecraftAccount fresh = LoginHelper.refreshAccount(activity, uuid);
        if (fresh == null && !API.hasConnection(activity)) { API.currentAcc = acc; return acc; } // offline: singleplayer still works
        return fresh;
    }

    // ------------------------------------------------------------------ install

    public static boolean installed(Context ctx, Profile p) {
        Constants.initConstants(ctx);
        try {
            MinecraftInstances.Instance i = API.loadAll().load(p.instanceName);
            return i != null && i.classpath != null && i.versionName.equals(version(ctx, p));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads (or updates) everything the profile needs. Blocking: call from a worker thread.
     * Minecraft, its libraries and assets come from Mojang, Fabric from FabricMC, mods from
     * QuestCraft's list (and Modrinth for ViveMonkeCraft), exactly as QuestCraft does.
     */
    public static void prepare(Activity activity, Profile p, Progress progress) throws Exception {
        if (!McRuntime.supported()) throw new IOException("Este celular não é ARM 64 bits; o Java do QuestCraft só existe para ARM 64.");
        Constants.initConstants(activity);
        McRuntime.installAll(activity, (what, f) -> progress.step(what));
        String version = version(activity, p);
        MinecraftInstances instances = API.loadAll();
        MinecraftInstances.Instance instance = instances.load(p.instanceName);
        File savesBackup = new File(Constants.USER_HOME, "saves-" + p.name().toLowerCase(java.util.Locale.ROOT));
        if (instance != null && (instance.classpath == null || !version.equals(instance.versionName))) {
            // half-installed or another Minecraft version: reinstall, keeping the worlds
            progress.step("Trocando para o Minecraft " + version);
            File saves = new File(instance.gameDir, "saves");
            File keep = savesBackup.exists() ? new File(savesBackup.getPath() + "-" + System.currentTimeMillis()) : savesBackup;
            if (saves.isDirectory() && !saves.renameTo(keep)) throw new IOException("Não deu para guardar os mundos antes de trocar de versão.");
            InstanceHandler.delete(instances, instance);
            instance = null;
        }
        if (instance == null) {
            progress.step("Baixando o Minecraft " + version + " e o Fabric");
            instance = API.createNewInstance(activity, instances, p.instanceName, true, version, "Fabric", null);
            if (instance.installing != null) {
                try {
                    instance.installing.join();
                } catch (Exception e) {
                    InstanceHandler.delete(instances, instance);
                    throw new IOException("A instalação parou: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                }
            }
            File saves = new File(instance.gameDir, "saves");
            if (savesBackup.isDirectory() && !saves.exists()) savesBackup.renameTo(saves);
        } else {
            progress.step("Atualizando os mods do QuestCraft");
            instance.updateMods(instances);
        }
        if (p == Profile.GORILLACRAFT) {
            progress.step("Adicionando o ViveMonkeCraft (movimento de gorila)");
            addViveMonkeCraft(instances, instance);
            writeGorillaTuning(instance);
        }
        tuneForPhone(instance, p);
        progress.step("Pronto");
    }

    /** Starts the game in its own process. */
    public static void play(Context ctx, Profile p) {
        MinecraftActivity.start(ctx, p.instanceName, accountUuid(ctx));
    }

    // ------------------------------------------------------------------ GorillaCraft

    private static void addViveMonkeCraft(MinecraftInstances instances, MinecraftInstances.Instance instance) throws Exception {
        String url = "https://api.modrinth.com/v2/project/vivemonkecraft/version?loaders="
                + URLEncoder.encode("[\"fabric\"]", "UTF-8") + "&game_versions=" + URLEncoder.encode("[\"" + instance.versionName + "\"]", "UTF-8");
        JSONArray versions = new JSONArray(get(url));
        if (versions.length() == 0) throw new IOException("O ViveMonkeCraft não tem versão para o Minecraft " + instance.versionName);
        JSONObject v = versions.getJSONObject(0);
        JSONArray files = v.getJSONArray("files");
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) file = files.getJSONObject(i);
        String fileName = file.getString("filename").replaceAll("\\.jar$", "");
        String number = v.getString("version_number");
        if (InstanceHandler.hasExtraProject(instance, "ViveMonkeCraft")) {
            pojlib.util.json.ProjectInfo old = null;
            for (pojlib.util.json.ProjectInfo info : instance.extProjects) if (info.slug.equalsIgnoreCase("ViveMonkeCraft")) old = info;
            if (old != null && number.equals(old.version) && new File(instance.gameDir, "mods/" + old.fileName + ".jar").exists()) return;
            InstanceHandler.removeExtraProject(instances, instance, "ViveMonkeCraft");
        }
        API.addExtraProject(instances, instance, "ViveMonkeCraft", fileName, number, file.getString("url"), "mod");
        File jar = new File(instance.gameDir, "mods/" + fileName + ".jar");
        McRuntime.download(file.getString("url"), jar, null);
        String sha512 = file.optJSONObject("hashes") != null ? file.getJSONObject("hashes").optString("sha512", "") : "";
        if (!sha512.isEmpty() && !sha512.equalsIgnoreCase(hash(jar, "SHA-512"))) {
            jar.delete();
            throw new IOException("O arquivo do ViveMonkeCraft veio diferente do publicado no Modrinth.");
        }
    }

    /**
     * ViveMonkeCraft settings for a phone in a VR Box (keys from its MovementConfig, configVersion 18).
     * Written once per tuning version; after that the in-game /vmc menu owns the file.
     */
    private static void writeGorillaTuning(MinecraftInstances.Instance instance) throws IOException {
        File marker = new File(instance.gameDir, "config/hypermagic-gorillacraft.version");
        if (marker.exists() && FileUtil.read(marker.getAbsolutePath()).trim().equals(String.valueOf(GORILLA_TUNING))) return;
        String props = "# GorillaCraft (HyperMagic) - ViveMonkeCraft tuned for VR Box hand tracking\n"
                + "configVersion=18\n"
                + "activePreset=HyperMagic VR Box\n"
                + "modToggled=true\n"
                // Gorilla Tag clone locomotion
                + "locomotionMode=1\n"
                // throws capped (6.5 in GT): a fast swing moves you, it does not launch you into the sky
                + "gtMaxJumpSpeed=4.2\n"
                + "gtJumpMultiplier=1.0\n"
                // hand shake from camera tracking stays below the speed that counts as a throw
                + "gtVelocityLimit=0.45\n"
                + "gtVelocityHistory=4\n"
                + "gtArmLength=1.5\n"
                // bigger hands so imprecise tracking still touches the ground and walls
                + "gtHandRadius=0.09\n"
                + "gtGravity=9.81\n"
                + "gtGroundFriction=0.7\n"
                // mobs don't push or carry you around (and it is lighter on the phone)
                + "gtEntityColliders=false\n"
                + "gtEntityTurnWithMob=false\n"
                + "gripSmoothing=0.75\n"
                + "singleFloorGrip=true\n"
                + "groundedGripRelease=true\n"
                + "handSurfaceCollision=true\n"
                + "clampHandModels=true\n"
                + "showHandMarkers=true\n"
                // no flying: no hoverboard, no teleport
                + "hoverboardEnabled=false\n"
                + "allowTeleport=false\n"
                // the gorilla body: no legs, shorter torso
                + "monkeModel=true\n"
                + "realMonke=false\n"
                + "cameraHeightOffset=0.0\n"
                + "surviveFatalFall=true\n"
                + "disableFallDamage=false\n"
                + "punchMining=false\n"
                + "debugLogging=false\n";
        File cfg = new File(instance.gameDir, "config/vivemonkecraft.properties");
        FileUtil.write(cfg.getAbsolutePath(), props.getBytes(StandardCharsets.UTF_8));
        FileUtil.write(marker.getAbsolutePath(), String.valueOf(GORILLA_TUNING).getBytes(StandardCharsets.UTF_8));
    }

    /** Lighter defaults for a phone (QuestCraft's options target a Quest 2/3). Only on first install. */
    private static void tuneForPhone(MinecraftInstances.Instance instance, Profile p) {
        File marker = new File(instance.gameDir, "config/hypermagic-options.version");
        if (marker.exists()) return;
        File options = new File(instance.gameDir, "options.txt");
        try {
            if (options.exists()) {
                String s = FileUtil.read(options.getAbsolutePath());
                s = s.replaceAll("(?m)^renderDistance:.*$", "renderDistance:" + (p == Profile.GORILLACRAFT ? 5 : 6))
                        .replaceAll("(?m)^simulationDistance:.*$", "simulationDistance:4")
                        .replaceAll("(?m)^maxFps:.*$", "maxFps:90")
                        .replaceAll("(?m)^particles:.*$", "particles:2")
                        .replaceAll("(?m)^entityShadows:.*$", "entityShadows:false");
                FileUtil.write(options.getAbsolutePath(), s.getBytes(StandardCharsets.UTF_8));
            }
            FileUtil.write(marker.getAbsolutePath(), "1".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.getInstance().appendToLog("options tuning: " + e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        // Modrinth asks every client to identify itself
        c.setRequestProperty("User-Agent", "HyperMagic/0.3 (github.com/mt-VR/HyperXR)");
        try (InputStream in = c.getInputStream()) {
            return FileUtil.read(in);
        } finally {
            c.disconnect();
        }
    }

    private static String hash(File f, String algorithm) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
