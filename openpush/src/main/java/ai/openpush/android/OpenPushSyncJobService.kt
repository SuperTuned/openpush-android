package ai.openpush.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Finishes durable SDK work shortly after the host app leaves the foreground. */
class OpenPushSyncJobService : JobService() {
    private val scope = sdkScope("OpenPush background sync")
    private var drainJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        drainJob = scope.launch {
            val succeeded = OpenPush.drainAll()
            jobFinished(params, !succeeded)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        drainJob?.cancel()
        drainJob = null
        return OpenPush.hasPendingWork()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val JOB_ID = 0x4F500001

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, OpenPushSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(1_000L)
                    .setOverrideDeadline(60_000L)
                    .setPersisted(false)
                    .build(),
            )
        }
    }
}
