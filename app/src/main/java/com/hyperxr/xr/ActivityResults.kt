package com.hyperxr.xr

import android.app.Activity
import android.content.Intent

/**
 * startActivityForResult from XR panels (system file picker): the immersive activity forwards
 * results here, keyed by a request code handed out per call.
 */
object ActivityResults {
    private val pending = HashMap<Int, (Intent?) -> Unit>()
    private var next = 0x4800

    fun launch(activity: Activity, intent: Intent, onResult: (Intent?) -> Unit) = activity.runOnUiThread {
        val code = synchronized(pending) { (next++).also { pending[it] = onResult } }
        try { activity.startActivityForResult(intent, code) }
        catch (_: Throwable) { synchronized(pending) { pending.remove(code) }?.invoke(null) }
    }

    /** Returns true when the result belonged to a launch() call. */
    fun dispatch(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val cb = synchronized(pending) { pending.remove(requestCode) } ?: return false
        cb(if (resultCode == Activity.RESULT_OK) data else null)
        return true
    }
}
