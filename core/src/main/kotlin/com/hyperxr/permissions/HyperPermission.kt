package com.hyperxr.permissions

/** How a permission is granted. */
enum class GrantPolicy {
    /** Granted on install when declared in the manifest; listed to the user before install. */
    INSTALL_TIME,
    /** Requires an explicit per-package user decision at first use / install. */
    USER_PROMPT,
    /** Requires an explicit confirmation with a warning; never granted silently or by default. */
    EXPLICIT_DANGEROUS,
}

/**
 * Permissions a `.hyperx` package can declare. The sandbox exposes an API table only when the
 * matching permission is granted; undeclared APIs are simply absent from the script environment.
 */
enum class HyperPermission(val key: String, val policy: GrantPolicy, val description: String) {
    UI("ui", GrantPolicy.INSTALL_TIME,
        "Create windows, panels, text, images and widgets (hx.ui)."),
    AUDIO("audio", GrantPolicy.INSTALL_TIME,
        "Play sounds and spatial audio from the package's own assets (hx.audio)."),
    INPUT("input", GrantPolicy.INSTALL_TIME,
        "Read controller buttons/axes, Joy-Con pointers and screen taps (hx.input)."),
    SPATIAL("spatial", GrantPolicy.INSTALL_TIME,
        "Read the head pose and place content in the user's space (hx.spatial)."),
    PHYSICS("physics", GrantPolicy.INSTALL_TIME,
        "Use the rigid-body physics world (hx.physics)."),
    HAND_TRACKING("hand_tracking", GrantPolicy.USER_PROMPT,
        "Read hand joint poses and gestures. Reveals hand movement but no camera pixels (hx.hands)."),
    FILESYSTEM("filesystem", GrantPolicy.USER_PROMPT,
        "Read/write files inside the package's private data folder only (hx.fs). Package assets are always readable."),
    NETWORK("network", GrantPolicy.USER_PROMPT,
        "HTTPS requests to hosts listed in manifest.network.allowedHosts only (hx.net)."),
    CAMERA("camera", GrantPolicy.USER_PROMPT,
        "Access camera frames. Privacy sensitive; requires the Android camera permission too (hx.camera)."),
    UNSAFE("unsafe", GrantPolicy.EXPLICIT_DANGEROUS,
        "Disables sandbox protections: LuaJIT FFI (native memory/code access), debug library, bytecode loading and " +
            "execution budgets. The package can then do anything the HyperMagic game process can do.");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): HyperPermission? = byKey[key]
    }
}

/** Grant decisions for one package. Immutable; persisted by the app module. */
data class PermissionGrants(val packageId: String, val granted: Set<HyperPermission>, val denied: Set<HyperPermission> = emptySet()) {
    fun isGranted(p: HyperPermission) = p in granted
}

object PermissionPolicy {
    /**
     * Initial grants at install time: only INSTALL_TIME permissions that were declared.
     * Prompted and dangerous permissions start ungranted and need [grant] after a user decision.
     */
    fun initialGrants(packageId: String, declared: Set<HyperPermission>): PermissionGrants =
        PermissionGrants(packageId, declared.filter { it.policy == GrantPolicy.INSTALL_TIME }.toSet())

    /**
     * Applies a user decision. A permission that was not declared in the manifest can never be granted,
     * and UNSAFE additionally requires [explicitConfirmation] (the UI's typed/double confirmation).
     */
    fun grant(
        current: PermissionGrants,
        declared: Set<HyperPermission>,
        permission: HyperPermission,
        userApproved: Boolean,
        explicitConfirmation: Boolean = false,
        integrityVerified: Boolean = true,
    ): PermissionGrants {
        require(permission in declared) { "Permission ${permission.key} is not declared by ${current.packageId}" }
        if (!userApproved) return current.copy(granted = current.granted - permission, denied = current.denied + permission)
        if (permission == HyperPermission.UNSAFE) {
            require(explicitConfirmation) { "unsafe requires explicit confirmation" }
            require(integrityVerified) { "unsafe cannot be granted to a package that failed integrity checks" }
        }
        return current.copy(granted = current.granted + permission, denied = current.denied - permission)
    }

    fun revoke(current: PermissionGrants, permission: HyperPermission): PermissionGrants =
        current.copy(granted = current.granted - permission)
}
