package com.hyperxr.appworld

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperxr.diagnostics.Diagnostics

/**
 * Notification access: the user enables it once in Android settings. It is what lets HyperXR see
 * other apps' notifications (cards in the world) and their media sessions (the music object).
 */
class HxNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this; NotificationHub.reload(activeNotifications) }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) { NotificationHub.post(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { NotificationHub.remove(sbn.key) }

    companion object {
        @Volatile var instance: HxNotificationListener? = null
        fun component(ctx: Context) = ComponentName(ctx, HxNotificationListener::class.java)
        fun enabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(component(ctx).flattenToString()) == true
        fun openSettings(ctx: Context) {
            ctx.startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** One notification as the world shows it, with the app's own actions. */
class WorldNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val actions: List<Action>,
    val contentIntent: PendingIntent?,
) {
    class Action(val title: String, val intent: PendingIntent, val remoteInputs: Array<RemoteInput>?)
}

object NotificationHub {
    private val items = LinkedHashMap<String, WorldNotification>()
    @Volatile var version = 0; private set
    private const val MAX = 30

    fun list(): List<WorldNotification> = synchronized(items) { items.values.sortedByDescending { it.time } }

    fun reload(all: Array<StatusBarNotification>?) {
        synchronized(items) { items.clear() }
        all?.forEach { post(it) }
    }

    fun post(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // ongoing ones (music, downloads, VPN...) are not messages; media has its own object
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val ctx = HxNotificationListener.instance ?: return
        val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Throwable) { sbn.packageName }
        val actions = n.actions.orEmpty().mapNotNull { a -> a.actionIntent?.let { WorldNotification.Action(a.title?.toString().orEmpty(), it, a.remoteInputs) } }
        val wn = WorldNotification(sbn.key, sbn.packageName, label, title, text.take(400), sbn.postTime, actions, n.contentIntent)
        synchronized(items) {
            items[sbn.key] = wn
            while (items.size > MAX) items.remove(items.values.minByOrNull { it.time }!!.key)
        }
        version++
    }

    fun remove(key: String) { synchronized(items) { items.remove(key) }; version++ }

    fun dismiss(key: String) {
        try { HxNotificationListener.instance?.cancelNotification(key) } catch (t: Throwable) { Diagnostics.log("dismiss failed: ${t.message}") }
        remove(key)
    }

    /** Fires an action; [reply] fills its RemoteInput (quick reply) when the app offers one. */
    fun run(ctx: Context, a: WorldNotification.Action, reply: String?) {
        try {
            val ri = a.remoteInputs?.firstOrNull()
            if (ri != null && reply != null) {
                val fill = android.content.Intent()
                RemoteInput.addResultsToIntent(arrayOf(ri), fill, android.os.Bundle().apply { putCharSequence(ri.resultKey, reply) })
                a.intent.send(ctx, 0, fill)
            } else a.intent.send()
        } catch (t: Throwable) { Diagnostics.log("Notification action failed: ${t.message}") }
    }
}

/** The media session of whatever app is playing (Spotify, YouTube Music, podcasts...). */
object MediaHub {
    class Now(val packageName: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean, val positionMs: Long, val durationMs: Long)

    @Volatile var now: Now? = null; private set
    @Volatile var version = 0; private set
    private var controller: MediaController? = null
    private var listening = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
        override fun onSessionDestroyed() { controller = null; refresh() }
    }

    /** Starts following active sessions; needs notification access. Main thread. */
    fun start(ctx: Context) {
        if (listening || !HxNotificationListener.enabled(ctx)) return
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = HxNotificationListener.component(ctx)
        try {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list) }, comp)
            pick(msm.getActiveSessions(comp))
            listening = true
        } catch (t: SecurityException) { Diagnostics.log("Media sessions not allowed: ${t.message}") }
    }

    private fun pick(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        now = c?.let {
            val m = it.metadata
            val st = it.playbackState
            Now(it.packageName, m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                st?.state == PlaybackState.STATE_PLAYING, st?.position ?: 0, m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
        }
        version++
    }

    fun playPause() { val c = controller ?: return; if (now?.playing == true) c.transportControls.pause() else c.transportControls.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
}
