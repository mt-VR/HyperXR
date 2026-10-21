package com.hyperxr.xr

/** Immersive session showing the spatial launcher (Dock or New), in MR or VR SBS. */
class XrActivity : ImmersiveActivity() {
    override fun createContent(): XrContent = SpatialLauncher(this, root)
}
