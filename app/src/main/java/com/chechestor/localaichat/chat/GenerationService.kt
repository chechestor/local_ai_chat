package com.chechestor.localaichat.chat

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
import com.chechestor.localaichat.MainActivity
import com.chechestor.localaichat.R

/**
 * Foreground service на время генерации ответа (F004 п. 9): система не останавливает процесс,
 * если приложение свёрнуто. Уведомление «Local AI Chat отвечает…» исчезает по окончании ответа.
 */
class GenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(this))
        }
        instance = this
        // Ответ мог закончиться раньше, чем сервис успел стартовать: startForeground обязателен, затем сразу стоп.
        if (!wanted) finish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 1002

        @Volatile private var wanted = false
        @Volatile private var instance: GenerationService? = null

        fun start(context: Context) {
            wanted = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java)) }
                .onFailure { wanted = false }
        }

        /** Не вызывает stopService до startForeground (иначе система роняет приложение). */
        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            wanted = false
            instance?.finish()
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ответ модели", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Local AI Chat отвечает…")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}
