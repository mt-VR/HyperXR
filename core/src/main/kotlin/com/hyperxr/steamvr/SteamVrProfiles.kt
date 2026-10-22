package com.hyperxr.steamvr

/**
 * Picks what a phone can stream smoothly: stream size, frame rate, codec, bitrate and how much of
 * the phone's own work (hand tracking rate, chromatic correction) to keep. A weak phone (2-4 GB,
 * Mali-G71 MP2 / Exynos 7904 class) gets 1920x1080 at 60 Hz in HEVC, which its decoder handles in
 * real time; bigger phones get more.
 */
object SteamVrProfiles {
    enum class Profile(val label: String) { AUTO("Automático"), LIGHT("Leve"), BALANCED("Equilibrado"), QUALITY("Qualidade") }

    data class Plan(
        val profile: Profile,
        val eyeWidth: Int,
        val eyeHeight: Int,
        val refreshHz: Int,
        val codecs: Int,          // bit 0 H.264, bit 1 HEVC: what the phone decodes at this size
        val maxMbps: Int,
        val handHz: Int,
        val chroma: Boolean,      // chromatic aberration correction (3 texture reads per pixel)
    )

    const val CODEC_H264 = 1
    const val CODEC_HEVC = 2

    /** SoCs that decode 1080p60 but little more, and struggle with ARCore + hands + decode at once. */
    private val LOW_END = listOf("exynos7904", "exynos7884", "exynos7885", "exynos850", "exynos9611", "s5e3830", "s5e8825",
        "mt6765", "mt6762", "mt6768", "mt6769", "mt6785", "sdm450", "sdm632", "sm6115", "sm6125", "sdm660", "sm6225", "universal7904", "universal9611")

    fun autoProfile(ramMb: Long, soc: String): Profile {
        val s = soc.lowercase()
        return when {
            ramMb in 1..4500 || LOW_END.any { s.contains(it) } -> Profile.LIGHT
            ramMb in 1..6500 -> Profile.BALANCED
            else -> Profile.QUALITY
        }
    }

    /**
     * [decodes] answers whether a hardware decoder for the codec (CODEC_*) handles a frame of that
     * size at that rate.
     */
    fun plan(requested: Profile, screenW: Int, screenH: Int, displayHz: Float, ramMb: Long, soc: String,
             decodes: (codec: Int, w: Int, h: Int, fps: Int) -> Boolean): Plan {
        val profile = if (requested == Profile.AUTO) autoProfile(ramMb, soc) else requested
        val landscapeW = maxOf(screenW, screenH); val landscapeH = minOf(screenW, screenH)
        // stream size cap per profile (both eyes side by side) and the frame rate
        val (capW, capH) = when (profile) {
            Profile.LIGHT -> 1920 to 1080
            Profile.BALANCED -> 2560 to 1440
            else -> 3200 to 1600
        }
        val refresh = when {
            profile == Profile.LIGHT -> 60
            displayHz >= 89f && profile == Profile.QUALITY -> 90
            displayHz >= 71f -> 72
            else -> 60
        }
        // start from the screen (more pixels than it shows are wasted) and shrink to fit
        var scale = minOf(1f, capW.toFloat() / landscapeW, capH.toFloat() / landscapeH)
        var codecs = 0
        var w = 0; var h = 0
        for (attempt in 0 until 12) {
            w = align(landscapeW * scale, 32); h = align(landscapeH * scale, 8)
            codecs = (if (decodes(CODEC_HEVC, w, h, refresh)) CODEC_HEVC else 0) or (if (decodes(CODEC_H264, w, h, refresh)) CODEC_H264 else 0)
            if (codecs != 0) break
            scale *= 0.9f
        }
        if (codecs == 0) codecs = CODEC_H264  // nothing reported: try the most common decoder anyway
        val maxMbps = when (profile) { Profile.LIGHT -> 25; Profile.BALANCED -> 45; else -> 80 }
        val handHz = when (profile) { Profile.LIGHT -> 12; Profile.BALANCED -> 20; else -> 30 }
        return Plan(profile, w / 2, h, refresh, codecs, maxMbps, handHz, chroma = profile != Profile.LIGHT)
    }

    /** Width in multiples of 32 (each eye a multiple of 16), height of 8: what encoders and decoders take as is. */
    private fun align(v: Float, to: Int): Int = maxOf(to, (v.toInt() / to) * to)
}
