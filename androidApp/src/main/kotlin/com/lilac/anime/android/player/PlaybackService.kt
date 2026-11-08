package com.lilac.anime.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lilac.anime.android.MainActivity
import com.lilac.anime.android.R
import java.lang.ref.WeakReference

/** 백그라운드 재생 (iOS background audio): a media-playback foreground service while the player plays, so the sound goes
 *  on with the app in the background; its notification pauses and resumes the player. */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = current.get()
        when (intent?.action) {
            ACTION_TOGGLE -> engine?.toggle()
            ACTION_STOP -> { engine?.pause(); stopSelf(); return START_NOT_STICKY }
        }
        if (engine == null) { stopSelf(); return START_NOT_STICKY }
        val notification = build(engine.paused)
        runCatching {
            ServiceCompat.startForeground(this, 1, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun build(paused: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "재생", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(subtitle).setContentIntent(open)
            .setOngoing(!paused).setSilent(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, if (paused) "재생" else "일시정지", action(ACTION_TOGGLE))
            .addAction(0, "멈추기", action(ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ACTION_TOGGLE = "lilac.toggle"
        private const val ACTION_STOP = "lilac.stop"
        private var current = WeakReference<MpvEngine>(null)
        private var title = ""
        private var subtitle = ""
        fun start(context: Context, engine: MpvEngine, title: String, subtitle: String) {
            current = WeakReference(engine); this.title = title; this.subtitle = subtitle
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
        }
        fun stop(context: Context) {
            current = WeakReference(null)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
