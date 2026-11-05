package com.hypermagic.hyperdreamed

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.hyperxr.daydream.VrServicesPatcher
import com.hyperxr.xrapps.ApkBundle
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** One APK the user brought: copied into the app, a bundle merged into one APK first. */
class Imported(val name: String, val apk: File, var report: VrServicesPatcher.Report, var patched: File? = null, var status: String = "")

object Patching {
    private const val KEY_ALIAS = "hyperdreamed-signing"

    /** Copies [uri] into the app's storage; split bundles (.apkm, .xapk, .apks) become one APK. */
    fun import(ctx: Context, uri: Uri): Imported {
        val name = displayName(ctx, uri) ?: "app.apk"
        val dir = File(ctx.filesDir, "imported").apply { mkdirs() }
        val raw = File(dir, "raw-${System.nanoTime()}")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> raw.outputStream().use { i.copyTo(it) } }
        val apk = File(dir, "in-${System.nanoTime()}.apk")
        if (ApkBundle.isBundle(raw)) { ApkBundle.merge(raw, apk, Build.SUPPORTED_ABIS.toList()); raw.delete() } else raw.renameTo(apk)
        val report = VrServicesPatcher.patch(apk, File(ctx.cacheDir, "unused.apk"), dryRun = true)
        return Imported(name, apk, report)
    }

    /** Patches and signs; the result is ready to install. */
    fun patchAndSign(ctx: Context, item: Imported) {
        val work = File(ctx.filesDir, "patched").apply { mkdirs() }
        val unsigned = File(ctx.cacheDir, "unsigned-${System.nanoTime()}.apk")
        item.report = VrServicesPatcher.patch(item.apk, unsigned)
        val out = File(work, "${item.report.packageName}-hyperdreamed.apk")
        val (key, cert) = signingKey()
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("hyperdreamed", key, listOf(cert)).build()))
            .setInputApk(unsigned).setOutputApk(out)
            .setLibraryPageAlignmentBytes(16384)
            .setCreatedBy("HyperDreamed")
            .build().sign()
        unsigned.delete()
        item.patched = out
    }

    /** Installs [apk] with the system installer (it asks on screen); [done] gets (ok, message). */
    fun install(ctx: Context, apk: File, done: (Boolean, String) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("base.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            InstallResult.pending[id] = done
            val intent = Intent(ctx, InstallResult::class.java).putExtra("session", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /** Opens the system screen that uninstalls [pkg]. */
    fun uninstall(ctx: Context, pkg: String) {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    enum class Signer { NOT_INSTALLED, THIS_PHONE, OTHER }

    /** Who signed the installed [pkg]: this app's key (patched here), someone else (Google), or nobody. */
    fun signer(ctx: Context, pkg: String): Signer {
        val certs = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() }.orEmpty()
            }
        } catch (_: PackageManager.NameNotFoundException) { return Signer.NOT_INSTALLED }
        val mine = runCatching { signingKey().second.encoded }.getOrNull() ?: return Signer.OTHER
        val d = MessageDigest.getInstance("SHA-256")
        val mineHash = d.digest(mine)
        return if (certs.any { MessageDigest.getInstance("SHA-256").digest(it).contentEquals(mineHash) }) Signer.THIS_PHONE else Signer.OTHER
    }

    fun installedVersion(ctx: Context, pkg: String): String? =
        try { ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?" } catch (_: Throwable) { null }

    /** RSA key made inside the Android keystore on first use; every patched app is signed with it. */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=HyperDreamed patched"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotAfter(end.time)
                    .build())
            }.generateKeyPair()
        }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

/** Result of a PackageInstaller session; asks the user when the system needs a confirmation. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("session", -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> pending.remove(id)?.invoke(true, "Instalado")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro $status"
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || "signatures" in msg.lowercase() || "INCOMPATIBLE" in msg)
                    " — desinstale a versão original primeiro (a assinatura é diferente)" else ""
                pending.remove(id)?.invoke(false, "Não instalou: $msg$hint")
            }
        }
    }

    companion object { val pending = java.util.concurrent.ConcurrentHashMap<Int, (Boolean, String) -> Unit>() }
}
