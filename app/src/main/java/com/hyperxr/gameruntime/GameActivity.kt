package com.hyperxr.gameruntime

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.hyperxr.HyperXRApp
import com.hyperxr.permissions.HyperPermission
import com.hyperxr.xr.ImmersiveActivity
import com.hyperxr.xr.XrContent

/** Runs one installed `.hyperx` package. Declared with android:process=":game". */
class GameActivity : ImmersiveActivity() {
    override fun createContent(): XrContent? {
        val id = intent.getStringExtra(EXTRA_PACKAGE) ?: return null
        val pkg = HyperXRApp.instance.packages.load(id) ?: run {
            Toast.makeText(this, "Package $id is not installed", Toast.LENGTH_LONG).show()
            return null
        }
        // Undecided prompted permissions are treated as denied; they are asked before launch.
        return GameRuntimeContent(pkg) { reason ->
            if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "com.hyperxr.extra.PACKAGE"

        /** Permissions that still need a user decision before the package may start. */
        fun pendingPrompts(id: String): List<HyperPermission> =
            HyperXRApp.instance.packages.load(id)?.pendingPrompts?.filter { it != HyperPermission.UNSAFE }.orEmpty()

        fun launch(context: Context, packageId: String) {
            context.startActivity(Intent(context, GameActivity::class.java).putExtra(EXTRA_PACKAGE, packageId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
