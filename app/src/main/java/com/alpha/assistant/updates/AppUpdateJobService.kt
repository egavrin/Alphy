package com.alpha.assistant.updates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Pair<JobParameters, Job>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(AppUpdater.TRANSFER_ID) ?: return false
        val updater = AppUpdater.get(application)
        val channel = "app-update-download"
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(channel, getString(R.string.update_title), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun notification(percent: Int, waiting: Boolean): Notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.update_title))
            .setContentText(getString(if (waiting) R.string.update_waiting else R.string.update_downloading, percent))
            .setProgress(100, percent, false)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        val current = updater.state.value as? UpdateState.Downloading
        setNotification(params, NOTIFICATION_ID, notification(current?.percent ?: 0, current?.waiting ?: false),
            JOB_END_NOTIFICATION_POLICY_REMOVE)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val progress = launch {
                updater.state.collect { state ->
                    if (state is UpdateState.Downloading) {
                        setNotification(params, NOTIFICATION_ID, notification(state.percent, state.waiting),
                            JOB_END_NOTIFICATION_POLICY_REMOVE)
                    }
                }
            }
            try {
                val retry = updater.runTransfer(id)
                if (work?.first === params) {
                    work = null
                    jobFinished(params, retry)
                }
            } finally {
                progress.cancel()
            }
        }
        work = params to job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val current = work ?: return false
        val id = params.extras.getString(AppUpdater.TRANSFER_ID) ?: return false
        if (current.first.jobId != params.jobId || current.first.extras.getString(AppUpdater.TRANSFER_ID) != id) {
            return false
        }
        work = null
        current.second.cancel()
        return AppUpdater.get(application).pause(id)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 8173
    }
}
