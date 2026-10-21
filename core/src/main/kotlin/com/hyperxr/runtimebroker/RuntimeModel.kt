package com.hyperxr.runtimebroker

/** How a runtime was found on the device. */
enum class RuntimeSource {
    /** Reported as active by the Khronos runtime broker content provider. */
    BROKER_ACTIVE,
    /** Found as an installed service advertising org.khronos.openxr.OpenXRRuntimeService. */
    SERVICE_DISCOVERY,
}

data class RuntimeDescriptor(
    val packageName: String,
    val label: String,
    val source: RuntimeSource,
    val soFilename: String? = null,
    val nativeLibDir: String? = null,
    val versionName: String? = null,
    val enabled: Boolean = true,
) {
    val isMonado: Boolean get() = packageName.startsWith("org.freedesktop.monado")
}

/** Result of actually initialising the loader against a runtime (filled by the native probe). */
data class RuntimeProbe(
    val ok: Boolean,
    val runtimeName: String = "",
    val runtimeVersion: String = "",
    val systemName: String = "",
    val extensions: List<String> = emptyList(),
    val error: String? = null,
)

object RuntimeSelector {
    /**
     * Picks the runtime to use:
     *  1. an explicit user choice, if still installed;
     *  2. Monado, when preferred and installed (it must be the broker's active runtime for the
     *     loader to use it, which the UI explains);
     *  3. the broker's active runtime;
     *  4. any discovered runtime.
     */
    fun select(runtimes: List<RuntimeDescriptor>, preferredPackage: String?, preferMonado: Boolean): RuntimeDescriptor? {
        val usable = runtimes.filter { it.enabled }
        preferredPackage?.let { p -> usable.firstOrNull { it.packageName == p }?.let { return it } }
        if (preferMonado) usable.firstOrNull { it.isMonado }?.let { return it }
        return usable.firstOrNull { it.source == RuntimeSource.BROKER_ACTIVE } ?: usable.firstOrNull()
    }

    /** Extensions HyperXR uses when present, and whether each is required. */
    val WANTED_EXTENSIONS = linkedMapOf(
        "XR_KHR_android_create_instance" to true,
        "XR_KHR_opengl_es_enable" to true,
        "XR_EXT_hand_tracking" to false,
        "XR_FB_passthrough" to false,
        "XR_EXT_hand_interaction" to false,
        "XR_KHR_composition_layer_depth" to false,
    )

    fun missingRequired(probe: RuntimeProbe): List<String> =
        WANTED_EXTENSIONS.filter { it.value && it.key !in probe.extensions }.keys.toList()
}

enum class SessionState { IDLE, CREATING, READY, RUNNING, STOPPING, ERROR }

/**
 * Guarantees a single XR session at a time across HyperXR (launcher, games, OpenXR path),
 * so two components never fight over the runtime, camera or display.
 */
class SessionArbiter {
    var state: SessionState = SessionState.IDLE
        private set
    var owner: String? = null
        private set
    var lastError: String? = null
        private set
    private val history = ArrayDeque<String>()

    @Synchronized
    fun acquire(requester: String): Boolean {
        if (state != SessionState.IDLE && state != SessionState.ERROR) {
            log("$requester denied: session owned by $owner ($state)")
            return false
        }
        owner = requester
        lastError = null
        transition(SessionState.CREATING)
        return true
    }

    @Synchronized
    fun transition(next: SessionState) {
        val allowed = when (state) {
            SessionState.IDLE -> next == SessionState.CREATING
            SessionState.CREATING -> next in setOf(SessionState.READY, SessionState.ERROR, SessionState.STOPPING)
            SessionState.READY -> next in setOf(SessionState.RUNNING, SessionState.STOPPING, SessionState.ERROR)
            SessionState.RUNNING -> next in setOf(SessionState.READY, SessionState.STOPPING, SessionState.ERROR)
            SessionState.STOPPING -> next in setOf(SessionState.IDLE, SessionState.ERROR)
            SessionState.ERROR -> next in setOf(SessionState.IDLE, SessionState.CREATING)
        }
        check(allowed) { "Illegal session transition $state -> $next" }
        log("$state -> $next (${owner ?: "-"})")
        state = next
        if (next == SessionState.IDLE) owner = null
    }

    @Synchronized
    fun fail(error: String) {
        lastError = error
        log("error: $error")
        state = SessionState.ERROR
    }

    @Synchronized
    fun release(requester: String) {
        if (owner != requester) return
        if (state == SessionState.RUNNING || state == SessionState.READY || state == SessionState.CREATING) transition(SessionState.STOPPING)
        if (state == SessionState.STOPPING || state == SessionState.ERROR) transition(SessionState.IDLE)
    }

    @Synchronized
    fun history(): List<String> = history.toList()

    private fun log(s: String) {
        history.addLast(s)
        while (history.size > 64) history.removeFirst()
    }
}
