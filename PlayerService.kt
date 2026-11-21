package com.exfiles.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaPlayer
import android.os.*
import java.io.File

class PlayerService : Service() {
    companion object {
        var mp: MediaPlayer? = null
        var list: List<String> = emptyList()
        var idx = 0
        var seek = 0
        var active = false

        fun title(): String = list.getOrNull(idx)?.let { File(it).name } ?: ""
        fun playing(): Boolean = try { mp?.isPlaying == true } catch (e: Exception) { false }

        fun play(c: Context, paths: List<String>, i: Int, pos: Int = 0) {
            if (paths.isEmpty()) return
            list = paths; idx = i.coerceIn(0, paths.size - 1); seek = pos
            val intent = Intent(c, PlayerService::class.java).setAction("play")
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
        }

        fun cmd(c: Context, a: String) {
            c.startService(Intent(c, PlayerService::class.java).setAction(a))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "play" -> { active = true; notif(); load() }
            "toggle" -> mp?.let { if (it.isPlaying) it.pause() else it.start() }
            "next" -> step(1)
            "prev" -> step(-1)
            "stop" -> { stopAll(); return START_NOT_STICKY }
        }
        if (active) notif() else stopSelf()
        return START_NOT_STICKY
    }

    private fun step(d: Int) {
        if (list.isEmpty()) return
        idx = (idx + d + list.size) % list.size
        load()
    }

    private fun load() {
        mp?.release(); mp = null
        var tries = 0
        while (tries < list.size) {
            val m = MediaPlayer()
            try {
                m.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                m.setDataSource(list[idx]); m.prepare()
                if (seek > 0) m.seekTo(seek)
                seek = 0
                m.setOnCompletionListener { step(1); if (active) notif() }
                m.start(); mp = m
                return
            } catch (e: Exception) {
                m.release(); idx = (idx + 1) % list.size; tries++
            }
        }
        stopAll()
    }

    private fun stopAll() {
        mp?.release(); mp = null; active = false
        stopForeground(true); stopSelf()
    }

    private fun notif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("pl", "Player", NotificationManager.IMPORTANCE_LOW))
        fun pi(a: String) = PendingIntent.getService(this, a.hashCode(),
            Intent(this, PlayerService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(ic: Int, t: String, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, ic), t, pi(a)).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "pl") else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title()).setContentIntent(open).setOngoing(true)
        b.addAction(act(android.R.drawable.ic_media_previous, "prev", "prev"))
        b.addAction(act(if (playing()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "play", "toggle"))
        b.addAction(act(android.R.drawable.ic_media_next, "next", "next"))
        b.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "stop", "stop"))
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, 2) else startForeground(1, n)
    }

    override fun onDestroy() {
        mp?.release(); mp = null; active = false
        super.onDestroy()
    }
}
