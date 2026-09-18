package it.danielebufarini.trenify.app

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MonitoringJobService : JobService() {
    private var graph: AppGraph? = null
    private var work: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartJob(params: JobParameters): Boolean {
        val appGraph = createAndroidAppGraph(applicationContext)
        graph = appGraph
        work = scope.launch {
            // Cancellation-safe handling: [runBackgroundRefreshOutcome]
            // rethrows CancellationException instead of swallowing it into a
            // success/failure result like runCatching would.
            try {
                val succeeded = runBackgroundRefreshOutcome {
                    appGraph.performBestEffortBackgroundRefresh()
                }
                appGraph.close()
                graph = null
                jobFinished(params, !succeeded)
            } catch (cancelled: CancellationException) {
                appGraph.close()
                graph = null
                throw cancelled
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        graph?.close()
        graph = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        graph?.close()
        super.onDestroy()
    }
}
