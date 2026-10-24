package com.hyperxr.steamvr

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Asks this phone's hardware decoders what they handle and plans the stream with SteamVrProfiles. */
object SteamVrPlanner {
    fun profile(ctx: Context): SteamVrProfiles.Profile =
        runCatching { SteamVrProfiles.Profile.valueOf(SteamVrActivity.prefs(ctx).getString("profile", "AUTO")!!) }.getOrDefault(SteamVrProfiles.Profile.AUTO)

    fun setProfile(ctx: Context, p: SteamVrProfiles.Profile) = SteamVrActivity.prefs(ctx).edit().putString("profile", p.name).apply()

    fun plan(ctx: Context, profile: SteamVrProfiles.Profile): SteamVrProfiles.Plan {
        val dm = ctx.resources.displayMetrics
        @Suppress("DEPRECATION")
        val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val soc = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null,
            android.os.Build.HARDWARE, android.os.Build.BOARD,
        ).joinToString(" ")
        return SteamVrProfiles.plan(profile, dm.widthPixels, dm.heightPixels, hz, mi.totalMem / (1024 * 1024), soc) { codec, w, h, fps ->
            decoder(mime(codec), w, h, fps) != null
        }
    }

    fun mime(codec: Int) = if (codec == SteamVrProfiles.CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** A hardware decoder that handles w×h at fps (software decoders are far too slow for this). */
    fun decoder(mime: String, w: Int, h: Int, fps: Int): String? {
        val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return null
        return list.firstOrNull { info ->
            !info.isEncoder && hardware(info) && info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                caps.areSizeAndRateSupported(w, h, fps.toDouble()) || (caps.isSizeSupported(w, h) && fps <= 60)
            }.getOrDefault(false)
        }?.name
    }

    private fun hardware(info: MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains("sw")
    }
}
