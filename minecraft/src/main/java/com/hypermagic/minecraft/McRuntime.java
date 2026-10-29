package com.hypermagic.minecraft;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import pojlib.util.Constants;
import pojlib.util.Logger;

/**
 * The parts of QuestCraft's launcher that are binaries: its Java 22 runtime (built by
 * QuestCraftPlusPlus/android-openjdk-build-multiarch), the LWJGL natives and GLFW classes of its
 * LWJGL fork, MobileGlues (desktop OpenGL on GLES), OpenAL, JNA and SQLite. They are downloaded on
 * the phone from the exact Pojlib commit HyperMagic was ported from and checked against their
 * SHA-256, instead of being committed to this repository or bundled in the APK.
 */
public final class McRuntime {
    private McRuntime() {}

    /** Pojlib, branch QuestCraft-6.0.1 (Minecraft 1.19.2 to 1.21.11). */
    public static final String POJLIB_COMMIT = "0e22a02a43e6705cbefa56ca00951db774ff116a";
    private static final String LFS = "https://media.githubusercontent.com/media/QuestCraftPlusPlus/Pojlib/" + POJLIB_COMMIT + "/src/main/";
    private static final String RAW = "https://raw.githubusercontent.com/QuestCraftPlusPlus/Pojlib/" + POJLIB_COMMIT + "/src/main/";
    private static final String JRE_URL = "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/jre22-6.0.0/JRE.zip";
    private static final String JRE_SHA256 = "50feec93a586162417735c51f3a0eaf7b31063b2deef1d3763be0761b53b22c1";
    private static final long JRE_BYTES = 40923924L;

    /** name, sha256, size (from the Git LFS pointers of that commit) */
    private static final String[][] NATIVES = {
            {"libjnidispatch.so", "abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06", "176520"},
            {"liblwjgl.so", "7cd38e1550505f47a941476a8ba8be0e2341822d1d66309fc7c8afb8b6ab5c37", "495456"},
            {"liblwjgl_lmdb.so", "990c87ca3e8b6895a8b8b4994bca5b3bc9f7652253bc35160270683134f1bf35", "88040"},
            {"liblwjgl_nanovg.so", "c599b785f8d871635a53c17efdf0c3e47a7135af4bf79f1a58fc82d319ab346c", "351376"},
            {"liblwjgl_opengl.so", "2e5b83ed440507a928306f955db73d48261a33d55f131d665df329ecb7b664e7", "360280"},
            {"liblwjgl_opengles.so", "437f171d392ba1d8153ba4a53d4e81cc9e5c8f89f4a70e9b26d97472cf3bd398", "149144"},
            {"liblwjgl_openvr.so", "4bda7698fcb065a5bd9d43f6132b333431c176a1c4ee73366d06b30418c13c5c", "5552"},
            {"liblwjgl_stb.so", "fbc9fb88310268f14150734a360c4e1839bd2bf81245d67b2bd4b38e63f43c46", "347408"},
            {"liblwjgl_tinyfd.so", "0da99be886e3b7f22f36616ea3425a512cf5ebdeceb40ff250ef1efb427d7f43", "81912"},
            {"libmobileglues.so", "b730cf564eb629cffdcdd18dbdbcfa64fedcb217cd7332ae81503f8382ca44b3", "5972576"},
            {"libmobileglues_info_getter.so", "56e1e92c5ad6803340ec14f712d2f2b824c69cd2949cdb11ae192806e9a83014", "899312"},
            {"libopenal.so", "2d218342fd1cb08080d708a8a4104b69cfc1209b47773bf41e9bb9126cb9ef8e", "15914936"},
            {"libsqlitejdbc.so", "ef6d472ec0713a6e457804e33c605d797729bc02cc859dab7f7b1bd01be170c2", "1144552"},
    };
    private static final String LWJGL_SHA256 = "7b10e5cbbd3c8395bd971f1c9d47675509025a745169045c2d7d6ee16d98f87c";
    private static final String RESCONF_SHA256 = "9b9e0df90d3555dfbe12b01f5fe0ec4f68ae116a35bc1d0c5a1ba327dd6e2d26";

    /** Progress of the current step, for the launcher UI. */
    public interface Progress { void step(String what, float fraction); }
    private static final Progress SILENT = (w, f) -> {};

    private static volatile File natives;

    /**
     * The Java runtime and natives exist only for 64-bit ARM (every VR Box phone of the last
     * years), and the app must run as a 64-bit process (not the 32-bit APK).
     */
    public static boolean supported() {
        if (!android.os.Process.is64Bit()) return false;
        for (String abi : Build.SUPPORTED_ABIS) if (abi.equals("arm64-v8a")) return true;
        return false;
    }

    public static File nativesDir(Context ctx) {
        if (natives == null) natives = new File(ctx.getFilesDir(), "minecraft-natives");
        return natives;
    }

    /** Absolute path of MobileGlues, the OpenGL that LWJGL loads. */
    public static String mobileGlues() {
        return natives == null ? "libmobileglues.so" : new File(natives, "libmobileglues.so").getAbsolutePath();
    }

    public static File jreDir(Context ctx) { return new File(ctx.getFilesDir(), "runtimes/JRE"); }

    /** Everything the game needs besides Minecraft itself, its mods and the account. */
    public static void installAll(Context ctx, Progress progress) throws IOException {
        if (progress == null) progress = SILENT;
        Constants.initConstants(ctx);
        installNatives(ctx, progress);
        installLwjgl(ctx);
        installResConfHack(ctx);
        installJre(ctx, false, progress);
    }

    public static void installNatives(Context ctx, Progress progress) throws IOException {
        File dir = nativesDir(ctx);
        dir.mkdirs();
        for (int i = 0; i < NATIVES.length; i++) {
            String[] n = NATIVES[i];
            File f = new File(dir, n[0]);
            if (f.length() == Long.parseLong(n[2]) && n[1].equals(sha256(f))) continue;
            progress.step("Baixando " + n[0], i / (float) NATIVES.length);
            download(LFS + "jniLibs/arm64-v8a/" + n[0], f, n[1]);
        }
    }

    /** QuestCraft's GLFW-on-Android classes for LWJGL; returns the jar path for the classpath. */
    public static String installLwjgl(Context ctx) throws IOException {
        Constants.initConstants(ctx);
        File jar = new File(Constants.USER_HOME, "lwjgl3/lwjgl-glfw-classes.jar");
        if (!LWJGL_SHA256.equals(sha256(jar))) download(RAW + "assets/lwjgl/lwjgl-glfw-classes.jar", jar, LWJGL_SHA256);
        return jar.getAbsolutePath();
    }

    /** DNS fix for Java on Android (SRV records for servers); on the game classpath. */
    public static void installResConfHack(Context ctx) throws IOException {
        Constants.initConstants(ctx);
        File jar = new File(Constants.USER_HOME, "hacks/ResConfHack.jar");
        if (!RESCONF_SHA256.equals(sha256(jar))) download(RAW + "assets/hacks/ResConfHack.jar", jar, RESCONF_SHA256);
    }

    public static void installJre(Context ctx, boolean force) throws IOException { installJre(ctx, force, SILENT); }

    public static void installJre(Context ctx, boolean force, Progress progress) throws IOException {
        File jre = jreDir(ctx);
        File stamp = new File(jre, ".hypermagic-sha256");
        if (!force && new File(jre, "lib/server/libjvm.so").exists() && JRE_SHA256.equals(readSmall(stamp))) return;
        progress.step("Baixando o Java 22 (" + JRE_BYTES / 1_000_000 + " MB)", 0f);
        File zip = new File(ctx.getCacheDir(), "JRE.zip");
        download(JRE_URL, zip, JRE_SHA256);
        progress.step("Instalando o Java 22", 0.5f);
        deleteTree(jre);
        unzip(zip, jre);
        zip.delete();
        // AWT without X11, built from Pojlib's source in this app
        File xawt = new File(ctx.getApplicationInfo().nativeLibraryDir, "libawt_xawt.so");
        if (xawt.exists()) Files.copy(xawt.toPath(), new File(jre, "lib/libawt_xawt.so").toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (OutputStream o = new FileOutputStream(stamp)) { o.write(JRE_SHA256.getBytes()); }
        Logger.getInstance().appendToLog("JRE installed from " + JRE_URL);
    }

    // ------------------------------------------------------------------ helpers

    static void download(String url, File out, String sha256) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(out.getPath() + ".part");
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "HyperMagic");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new IOException("download " + code + ": " + url); }
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            String got = sha256(tmp);
            if (sha256 != null && !sha256.equals(got)) {
                tmp.delete();
                throw new IOException("arquivo diferente do esperado: " + out.getName());
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) throw new IOException("não deu para salvar " + out);
            return;
        }
        throw new IOException("redirecionamentos demais: " + url);
    }

    static String sha256(File f) {
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readSmall(File f) {
        try { return f.isFile() ? new String(Files.readAllBytes(f.toPath())).trim() : ""; } catch (IOException e) { return ""; }
    }

    private static void unzip(File zip, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            byte[] buf = new byte[256 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("entrada fora da pasta: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (e.getName().startsWith("bin/")) f.setExecutable(true);
            }
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
