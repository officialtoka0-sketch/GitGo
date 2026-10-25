package com.gitgo.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

class TransferJob(val title: String, val block: suspend (Ctl) -> String)

object JobQueue {
    val queue = ConcurrentLinkedQueue<TransferJob>()

    fun submit(ctx: Context, title: String, block: suspend (Ctl) -> String) {
        queue.add(TransferJob(title, block))
        ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
    }
}

/** Передача выполняется в foreground-сервисе: уведомление показывает объём, скорость и оставшееся время. */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private val lock = Any()
    private var workerActive = false
    @Volatile private var lastStartId = 0
    @Volatile private var current: Deferred<String>? = null
    private var doneId = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            current?.cancel()
            // Сервис уже не работает — не оставляем «пустой» запущенный сервис.
            synchronized(lock) { if (!workerActive) stopSelf(startId) }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildProgress(Progress("GitGo", message = tr(R.string.svc_preparing))),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        synchronized(lock) {
            lastStartId = startId
            if (!workerActive) {
                workerActive = true
                worker = scope.launch { runQueue() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            // Решение «очередь пуста — выходим» принимается под замком, иначе задача, добавленная
            // в этот момент, навсегда останется в очереди, а сервис будет остановлен.
            val job = synchronized(lock) {
                val j = JobQueue.queue.poll()
                if (j == null) {
                    workerActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
                j
            } ?: return
            var last = 0L
            var lastP = Progress(job.title)
            val report: (Progress) -> Unit = { p ->
                lastP = p
                TransferState.current.value = p.copy(title = job.title.takeIf { p.title.isBlank() } ?: p.title)
                val now = SystemClock.elapsedRealtime()
                if (now - last > 600) {
                    last = now
                    nm.notify(NOTIF_ID, buildProgress(p))
                }
            }
            var dref: Deferred<String>? = null
            val ctl = Ctl(report) { dref?.isActive == false }
            val deferred = scope.async(Dispatchers.IO) { job.block(ctl) }
            dref = deferred
            current = deferred
            TransferState.current.value = Progress(job.title, message = tr(R.string.svc_starting))

            var state = Progress.State.DONE
            var msg: String
            try {
                msg = deferred.await()
            } catch (e: CancellationException) {
                if (!deferred.isCancelled) throw e
                // Блокирующая часть (JGit/сеть) ещё может работать — ждём её, чтобы следующая задача не пошла параллельно.
                withTimeoutOrNull(30_000) { deferred.join() }
                state = Progress.State.CANCELLED
                msg = tr(R.string.cancelled)
            } catch (e: Throwable) {
                state = Progress.State.ERROR
                msg = e.message ?: e.javaClass.simpleName
            }
            current = null

            val finalP = Progress(job.title, lastP.done, lastP.total, lastP.speed, 0, lastP.unit, state, msg)
            TransferState.current.value = finalP
            nm.notify(doneId++, buildDone(job.title, state, msg))
        }
    }

    /** Android 15: у dataSync-сервиса лимит ~6 часов — останавливаемся сами, иначе система падает с ANR. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        current?.cancel()
        stopSelf(startId)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgress(p: Progress): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (p.message.isNotBlank() && p.total == 0L) p.message else p.line()
        return NotificationCompat.Builder(this, GitGoApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(p.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, p.percent.coerceAtLeast(0), p.percent < 0)
            .setContentIntent(contentIntent())
            .addAction(0, tr(R.string.cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildDone(title: String, state: Progress.State, msg: String): Notification {
        val mark = when (state) {
            Progress.State.DONE -> "✔"
            Progress.State.CANCELLED -> "⏹"
            else -> "✖"
        }
        return NotificationCompat.Builder(this, GitGoApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$mark $title")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_CANCEL = "com.gitgo.app.CANCEL"
    }
}
