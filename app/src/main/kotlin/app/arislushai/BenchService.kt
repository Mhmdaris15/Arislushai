package app.arislushai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private const val CHANNEL = "recording"

/** Shared by both services. */
fun Service.enterForeground(title: String, text: String, type: Int) {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Арислушай", NotificationManager.IMPORTANCE_LOW))
    val n: Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()
    ServiceCompat.startForeground(this, 1, n, type)
}

fun Context.acquireWakeLock(tag: String): PowerManager.WakeLock =
    (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arislushai:$tag")
        .apply { acquire(4 * 60 * 60 * 1000L) } // safety cap: 4 h

/**
 * Runs the file benchmark in a foreground service. Without this, Android's app freezer
 * freezes the process once the screen turns off (observed on the A54: 0% CPU, thread parked in
 * do_freezer_trap), so benchmarks and, later, real transcription must never rely on an activity.
 */
class BenchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground("Арислушай · file benchmark", "Processing audio file", ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val spec = Models.byId(intent?.getStringExtra(EXTRA_MODEL) ?: "") ?: Models.all.first()
        val targets = intent?.getStringExtra(EXTRA_TARGETS)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val paced = intent?.getBooleanExtra(EXTRA_PACED, false) ?: false
        val wav = File(getExternalFilesDir(null)!!, intent?.getStringExtra(EXTRA_WAV) ?: "input.wav")
        val fd = getExternalFilesDir(null)!!

        if (AppBus.busy.value) {
            AppBus.log("Already running; ignored")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wav.exists() || !Models.isInstalled(fd, spec)) {
            AppBus.log("Missing input or model: wav=${wav.path} exists=${wav.exists()} model=${spec.id} installed=${Models.isInstalled(fd, spec)}")
            stopSelf()
            return START_NOT_STICKY
        }
        AppBus.busy.value = true
        val wl = acquireWakeLock("bench")
        scope.launch {
            try {
                runFileBenchmark(applicationContext, spec, wav, targets, paced)
            } catch (e: Throwable) {
                AppBus.log("File run failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (wl.isHeld) wl.release()
                AppBus.busy.value = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_PACED = "paced"
        const val EXTRA_WAV = "wav"

        fun start(ctx: Context, modelId: String, targets: List<String>, paced: Boolean, wavName: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BenchService::class.java)
                    .putExtra(EXTRA_MODEL, modelId)
                    .putExtra(EXTRA_TARGETS, targets.joinToString(","))
                    .putExtra(EXTRA_PACED, paced)
                    .putExtra(EXTRA_WAV, wavName),
            )
        }
    }
}
