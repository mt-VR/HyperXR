package com.hyperxr.compat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.xrapps.ApkPatcher
import com.hyperxr.xrapps.CompatLibraries
import com.hyperxr.xrapps.PatchReport
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * HyperXR's compatibility layer for OpenXR / Quest and Daydream / Cardboard apps (the patcher
 * follows Compatibility-Layer's port tool, third_party/compatibility-layer): patches APKs on the phone, signs them with a key that never
 * leaves this phone's keystore, and starts games so they can see the runtime, the broker and the
 * VrApi driver.
 */
object CompatLayer {
    private const val KEY_ALIAS = "hyperxr-game-signing"
    const val BROKER_PACKAGE = "com.hypermagic.broker"

    /** Copies the bundled Khronos loaders out of the APK (once per app update). */
    fun libraries(ctx: Context): CompatLibraries {
        val dir = File(ctx.filesDir, "compat")
        val stamp = File(dir, "version")
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        if (!stamp.isFile || stamp.readText() != version) {
            dir.deleteRecursively()
            for (abi in abis) for (lib in listOf("libopenxr_loader.so")) {
                val out = File(dir, "$abi/$lib").apply { parentFile?.mkdirs() }
                ctx.assets.open("compat/$abi/$lib").use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            stamp.writeText(version)
        }
        return CompatLibraries(abis.associateWith { File(dir, "$it/libopenxr_loader.so") })
    }

    fun plan(ctx: Context, apk: File): PatchReport = ApkPatcher.patch(apk, File(ctx.cacheDir, "unused.apk"), libraries(ctx), dryRun = true, device32Bit = android.os.Build.SUPPORTED_32_BIT_ABIS.isNotEmpty())

    /** Patches and signs [apk]; returns the report and the installable file (null when blocked). */
    fun patchAndSign(ctx: Context, apk: File, progress: (String) -> Unit): Pair<PatchReport, File?> {
        val work = File(ctx.cacheDir, "patch").apply { deleteRecursively(); mkdirs() }
        val unsigned = File(work, "unsigned.apk")
        progress("Corrigindo o APK...")
        val report = ApkPatcher.patch(apk, unsigned, libraries(ctx), device32Bit = android.os.Build.SUPPORTED_32_BIT_ABIS.isNotEmpty())
        if (report.blockers.isNotEmpty()) return report to null
        progress("Assinando...")
        val signed = File(work, "${report.packageName}-hyperxr.apk")
        val (key, cert) = signingKey()
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("hyperxr", key, listOf(cert)).build()))
            .setInputApk(unsigned).setOutputApk(signed)
            .setLibraryPageAlignmentBytes(16384)
            .setCreatedBy("HyperMagic")
            .build().sign()
        unsigned.delete()
        Diagnostics.log("Patched ${report.packageName}: ${report.changes.joinToString("; ")}")
        return report to signed
    }

    /** RSA key generated inside the Android keystore on first use (it cannot be extracted). */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=HyperMagic patched game"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotAfter(end.time)
                    .build())
            }.generateKeyPair()
        }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }

    /**
     * Writes the HyperXR viewer calibration where Cardboard / Daydream (GVR SDK) apps read their
     * viewer: /sdcard/Cardboard/current_device_params. Needs storage access; false when denied.
     */
    fun writeCardboardParams(ctx: Context): Boolean = try {
        val g = com.hyperxr.HyperXRApp.instance.settings.current.graphics
        val vw = com.hyperxr.viewers.ViewerCatalog.byId(g.viewerId)
        // Cardboard button types: 0 none, 1 magnet, 2 touch, 3 indirect touch
        val button = when (vw?.button) { com.hyperxr.viewers.ViewerCatalog.Button.MAGNET -> 1; com.hyperxr.viewers.ViewerCatalog.Button.NONE -> 0; else -> 3 }
        val v = com.hyperxr.xrapps.CardboardParams.fromSettings(g.lensSeparationMm, g.fovDegrees, g.distortionK1, g.distortionK2,
            vendor = vw?.brand ?: "HyperMagic", model = vw?.name ?: "VR Box", screenToLensMm = g.screenToLensMm, trayToLensMm = g.trayToLensMm, button = button)
        val f = File(android.os.Environment.getExternalStorageDirectory(), com.hyperxr.xrapps.CardboardParams.FILE_PATH)
        f.parentFile?.mkdirs()
        f.writeBytes(com.hyperxr.xrapps.CardboardParams.fileBytes(v))
        true
    } catch (t: Throwable) {
        Diagnostics.log("Cardboard viewer params not written: ${t.message}")
        false
    }

    fun installed(ctx: Context, pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }

    // ------------------------------------------------------------ HyperXR Daydream (vrcore module)

    const val DAYDREAM_PACKAGE = "com.google.vr.vrcore"
    enum class DaydreamState { NOT_INSTALLED, HYPERXR, GOOGLE }

    /** Our companion APK carries "-hyperxr" in its version name; anything else is Google VR Services. */
    fun daydreamState(ctx: Context): DaydreamState = try {
        val v = ctx.packageManager.getPackageInfo(DAYDREAM_PACKAGE, 0).versionName ?: ""
        if (v.endsWith("-hyperxr")) DaydreamState.HYPERXR else DaydreamState.GOOGLE
    } catch (_: Throwable) { DaydreamState.NOT_INSTALLED }

    fun daydreamStatus(ctx: Context): String = when (daydreamState(ctx)) {
        DaydreamState.HYPERXR -> "HyperMagic Daydream instalado: controle Daydream pelo Joy-Con e calibração do VR Box nos apps GVR"
        DaydreamState.GOOGLE -> "O Google VR Services ocupa o lugar: desinstale-o para usar o HyperMagic Daydream"
        DaydreamState.NOT_INSTALLED -> "Não instalado: apps Daydream ficam sem controle"
    }

    /** Installs (or updates) HyperXR Daydream from the APK inside HyperXR: Shizuku when ready, else the system installer. */
    fun installDaydream(ctx: Context, done: (String) -> Unit) {
        if (daydreamState(ctx) == DaydreamState.GOOGLE) { done("Desinstale o Google VR Services primeiro"); return }
        val apk = File(ctx.cacheDir, "hyperxr-daydream.apk")
        try { ctx.assets.open("compat/hyperxr-daydream.apk").use { i -> apk.outputStream().use { i.copyTo(it) } } }
        catch (t: Throwable) { done("APK do HyperMagic Daydream ausente: ${t.message}"); return }
        installApkFile(ctx, apk) { ok, msg -> done(if (ok) "HyperMagic Daydream instalado. Abra-o uma vez para permitir o Joy-Con (Shizuku)" else msg) }
    }

    /**
     * Installs [apk] as it is: through Shizuku when ready (silent), otherwise the system installer
     * asks on the phone's screen. [done] gets (installed now, message).
     */
    fun installApkFile(ctx: Context, apk: File, done: (Boolean, String) -> Unit) {
        if (com.hyperxr.shell.ShellBridge.state(ctx) == com.hyperxr.shell.ShizukuState.READY) {
            com.hyperxr.shell.ShellBridge.withService(ctx) { svc ->
                Thread {
                    val err = svc?.let { s -> android.os.ParcelFileDescriptor.open(apk, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { s.installApk(it, apk.length()) } } ?: "Shizuku não respondeu"
                    if (err.isEmpty()) done(true, "Instalado") else done(false, "Falha: $err")
                }.start()
            }
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            done(false, "Confirme a instalação na tela do celular")
        } catch (t: Throwable) { done(false, "Instalador do sistema indisponível: ${t.message}") }
    }

    /**
     * Shares the HyperConnect APK (inside HyperXR) to the other phone through Android's share sheet
     * (Nearby Share, Bluetooth, a messenger...). It opens on this phone's screen.
     */
    fun shareHyperConnect(ctx: Context): String = try {
        val apk = File(ctx.cacheDir, "HyperConnect.apk")
        ctx.assets.open("compat/hyperconnect.apk").use { i -> apk.outputStream().use { i.copyTo(it) } }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Enviar o HyperConnect para o outro celular").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Escolha como enviar, na tela do celular"
    } catch (t: Throwable) { "Não deu para compartilhar: ${t.message}" }

    /** Opens HyperXR Daydream's own screen (Shizuku permission, gyro axes, live test). */
    fun openDaydream(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(DAYDREAM_PACKAGE)
        ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false

    /**
     * Starts an XR game so that it can see what it needs: a game made for Android 11+ only sees
     * packages it declared, unless they granted it a content URI. HyperXR grants URIs of its
     * runtime config provider and of the OpenXR broker, which makes both visible.
     */
    fun launchGame(ctx: Context, pkg: String, activity: String) {
        val uris = buildList {
            add(Uri.parse("content://com.hypermagic.runtime/visibility/$pkg"))
            if (installed(ctx, BROKER_PACKAGE)) add(Uri.parse("content://org.khronos.openxr.runtime_broker/visibility/$pkg"))
            // GVR apps made for Android 11+ must see HyperXR Daydream to find its controller service
            if (daydreamState(ctx) == DaydreamState.HYPERXR) add(Uri.parse("content://com.google.vr.vrcore.settings/visibility/$pkg"))
        }
        val clip = ClipData.newRawUri("HyperMagic", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        val intent = Intent(Intent.ACTION_MAIN).setClassName(pkg, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = clip
        try { ctx.startActivity(intent) } catch (t: SecurityException) {
            // a provider refused the grant: start without it rather than not at all
            Diagnostics.log("Visibility grant refused (${t.message}); starting $pkg without it")
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(pkg, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
