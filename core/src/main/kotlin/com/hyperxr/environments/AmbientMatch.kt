package com.hyperxr.environments

/**
 * Makes the virtual world agree with the environment around it:
 *  - [lightFor]: 3D objects (the Hand Physics Lab, models, the hands' skeleton) are lit with the
 *    colour of the 360 panorama they sit in, instead of a fixed studio light: warm at sunset,
 *    purple on the night street;
 *  - [forTime]: the built-in panoramas follow the clock when the user turns that on.
 */
object AmbientMatch {
    class Light(val ambient: FloatArray, val lightColor: FloatArray)

    val STUDIO = Light(floatArrayOf(0.32f, 0.33f, 0.4f), floatArrayOf(1f, 0.97f, 0.92f))

    /**
     * From the panorama's average colour (0..1). Ambient keeps the hue but a usable level (a dark
     * night scene must not make objects black); the key light is white tinted toward the hue.
     */
    fun lightFor(r: Float, g: Float, b: Float): Light {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val max = maxOf(r, g, b, 1e-3f)
        // hue at full brightness, then scaled to an ambient level between 0.22 and 0.45
        val level = (0.22f + luma * 0.4f).coerceIn(0.22f, 0.45f)
        val ambient = floatArrayOf(r / max * level, g / max * level, b / max * level)
        val k = 0.35f
        val light = floatArrayOf(1f - k + k * r / max, 1f - k + k * g / max, 1f - k + k * b / max)
        val bright = (0.75f + luma * 0.5f).coerceIn(0.75f, 1.1f)
        for (i in 0..2) light[i] *= bright
        return Light(ambient, light)
    }

    /** Built-in environment for a time of day (24 h clock). */
    fun forTime(hour: Int, minute: Int = 0): String {
        val t = hour * 60 + minute
        return when {
            t < 5 * 60 + 30 -> "bundled:night_street"
            t < 7 * 60 -> "bundled:penthouse_sunset"       // dawn
            t < 9 * 60 -> "bundled:future_plaza"
            t < 17 * 60 -> "bundled:vr_studio"             // working hours
            t < 19 * 60 -> "bundled:penthouse_sunset"      // sunset
            t < 20 * 60 + 30 -> "bundled:future_plaza"
            else -> "bundled:night_street"
        }
    }
}
