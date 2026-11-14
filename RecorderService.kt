package com.hqrec.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

class RecorderService : Service() {

    companion object {
        @Volatile var recording = false
        const val ACTION_START = "com.hqrec.app.START"
        const val ACTION_STOP = "com.hqrec.app.STOP"
        private const val CHANNEL = "hqrec"
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRec()
            ACTION_START -> if (!recording && recorder == null) startRec(intent)
        }
        return START_NOT_STICKY
    }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HQ Recorder")
            .setContentText("Recording chal rahi hai - rokne ke liye STOP dabao")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "STOP", stopPi).build())
            .build()
    }

    private fun startRec(i: Intent) {
        val audio = AudioMode.values()[i.getIntExtra("audio", AudioMode.NONE.ordinal)]
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audio != AudioMode.NONE && micOk && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(1, notif(), type)

        val cfg = RecConfig(
            width = i.getIntExtra("w", 1920),
            height = i.getIntExtra("h", 1080),
            dpi = i.getIntExtra("dpi", 320),
            fps = i.getIntExtra("fps", 30),
            bitrate = i.getIntExtra("br", 16_000_000),
            audioMode = if (micOk) audio else AudioMode.NONE,
            hevc = i.getBooleanExtra("hevc", false),
            codecName = i.getStringExtra("codec") ?: ""
        )
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null) { stopRec(); return }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val p = mgr.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopRec() } }
        }, handler)

        val r = ScreenRecorder(applicationContext, p, cfg)
        recorder = r
        // 1.5 sec ruko taaki aap game kholne ke liye home par ja sako
        handler.postDelayed({
            try {
                r.start()
                recording = true
            } catch (e: Exception) {
                Toast.makeText(this, "Record shuru nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
                stopRec()
            }
        }, 1500)
    }

    private fun stopRec() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val r = recorder
        recorder = null
        val wasRecording = recording
        recording = false
        Thread {
            try { r?.stop() } catch (_: Exception) {}
            handler.post {
                try { projection?.stop() } catch (_: Exception) {}
                projection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (wasRecording) {
                    Toast.makeText(this, "Video save ho gayi: Movies/HQRecorder", Toast.LENGTH_LONG).show()
                }
                stopSelf()
            }
        }.start()
    }
}
