package com.dafei.voicenotes.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dafei.voicenotes.MainActivity
import com.dafei.voicenotes.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class RecordingService : Service() {
    data class Session(
        val isRecording: Boolean = false,
        val outputPath: String? = null,
        val startedAtElapsedRealtime: Long = 0L,
        /** Set when a recording finishes; UI should consume via [consumeCompleted]. */
        val completedPath: String? = null,
        val completedDurationMs: Long? = null,
        val error: String? = null
    )

    private val binder = LocalBinder()
    private val recorder = WavRecorder()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                if (path.isNullOrBlank()) {
                    stopSelfSafely()
                } else {
                    beginRecording(File(path))
                }
            }
            ACTION_STOP -> {
                finishRecording()
            }
            else -> {
                if (!session.value.isRecording) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    fun amplitudes(): List<Float> = recorder.amplitudes

    private fun beginRecording(output: File): Boolean {
        if (session.value.isRecording) return true
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            _session.update {
                it.copy(error = e.message ?: "无法启动前台录音服务")
            }
            stopSelfSafely()
            return false
        }

        val started = recorder.start(output)
        if (!started) {
            val err = recorder.lastError ?: "无法启动录音"
            _session.update {
                it.copy(isRecording = false, outputPath = null, error = err)
            }
            stopForegroundAndSelf()
            return false
        }

        _session.update {
            it.copy(
                isRecording = true,
                outputPath = output.absolutePath,
                startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                completedPath = null,
                completedDurationMs = null,
                error = null
            )
        }
        return true
    }

    private fun finishRecording() {
        if (!session.value.isRecording) {
            stopForegroundAndSelf()
            return
        }
        val path = session.value.outputPath
        val duration = recorder.stop()
        val err = recorder.lastError
        _session.update {
            it.copy(
                isRecording = false,
                outputPath = null,
                completedPath = path,
                completedDurationMs = duration,
                error = err
            )
        }
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_notification_title)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.recording_notification_stop), stopPi)
            .build()
    }

    override fun onDestroy() {
        if (session.value.isRecording) {
            val path = session.value.outputPath
            val duration = recorder.stop()
            _session.update {
                it.copy(
                    isRecording = false,
                    outputPath = null,
                    completedPath = path,
                    completedDurationMs = duration,
                    error = recorder.lastError
                )
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dafei.voicenotes.action.START_RECORDING"
        const val ACTION_STOP = "com.dafei.voicenotes.action.STOP_RECORDING"
        const val EXTRA_OUTPUT_PATH = "output_path"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        private val _session = MutableStateFlow(Session())
        val session: StateFlow<Session> = _session.asStateFlow()

        fun consumeCompleted(): Pair<String, Long>? {
            val current = _session.value
            val path = current.completedPath ?: return null
            val duration = current.completedDurationMs ?: return null
            _session.update {
                it.copy(completedPath = null, completedDurationMs = null)
            }
            return path to duration
        }

        fun clearError() {
            _session.update { it.copy(error = null) }
        }

        fun startIntent(context: Context, outputPath: String): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }

        fun startRecording(context: Context, outputPath: String) {
            ContextCompatStart.startForegroundService(context, startIntent(context, outputPath))
        }

        fun stopRecording(context: Context) {
            context.startService(stopIntent(context))
        }
    }
}

/** Avoid importing androidx.core.content.ContextCompat name clash in companion. */
private object ContextCompatStart {
    fun startForegroundService(context: Context, intent: Intent) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Binds to [RecordingService] while composed and exposes the live binder instance
 * for amplitude polling.
 */
class RecordingServiceClient(
    private val context: Context,
    private val onConnected: (RecordingService?) -> Unit
) {
    private var service: RecordingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? RecordingService.LocalBinder
            service = local?.getService()
            onConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            onConnected(null)
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun unbind() {
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
        }
        bound = false
        service = null
        onConnected(null)
    }
}
