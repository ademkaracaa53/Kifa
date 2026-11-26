package com.kifa.transkript

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Kayıt sürerken uygulamayı ön planda tutar: ekran kapansa ya da başka uygulamaya geçilse de
 * mikrofon ve metne çevirme devam eder. Kalan cümleler bitince kendini kapatır.
 */
class RecordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { Engine.stop(); return START_NOT_STICKY }
        }
        startInForeground()
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transkript:rec").apply { acquire(6 * 60 * 60 * 1000L) }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(watch, 1500)
        return START_NOT_STICKY
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!Engine.recording && !Engine.working) { stopSelf(); return }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Kayıt", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Transkript kaydediyor")
            .setContentText("Konuşmalar metne çevriliyor")
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 7, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CH = "rec"
        const val ACTION_STOP = "stop"
        fun start(ctx: Context) {
            val i = Intent(ctx, RecordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
