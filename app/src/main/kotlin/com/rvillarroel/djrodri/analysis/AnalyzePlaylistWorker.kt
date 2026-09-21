package com.rvillarroel.djrodri.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rvillarroel.djrodri.R
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.analysis.AnalysisProgress
import com.rvillarroel.djrodri.spotify.SpotifyApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data class Running(val done: Int, val total: Int, val title: String?) : AnalysisUiState
    data object Done : AnalysisUiState
    data class Failed(val message: String) : AnalysisUiState
}

/**
 * Analyzes a whole playlist in the background with a visible notification, so HyperOS lets it finish.
 * Every song is saved as it is done: if the work is killed, the next run continues from there.
 */
class AnalyzePlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getString(KEY_PLAYLIST) ?: return Result.failure()
        val refresh = inputData.getBoolean(KEY_REFRESH, false)
        val app = applicationContext.container
        runCatching { setForeground(foregroundInfo(0, 0)) }

        return runCatching {
            val tracks = app.library.tracks(playlistId, refresh = refresh)
            app.analyzer.analyze(tracks).collect { progress ->
                if (progress is AnalysisProgress.Working) {
                    setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total, KEY_TITLE to progress.currentTitle))
                    runCatching { setForeground(foregroundInfo(progress.done, progress.total)) }
                }
            }
            Result.success()
        }.getOrElse { error ->
            // A refusal (403, 404…) will say the same thing on every try; only network trouble and 429 are worth retrying.
            val permanent = error is SpotifyApiException && error.code in 400..499 && error.code != 429
            if (!permanent && runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Error desconocido")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Análisis de playlists", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_dj)
            .setContentTitle("Analizando playlist")
            .setContentText(if (total > 0) "$done de $total canciones" else "Preparando…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_PLAYLIST = "playlist"
        const val KEY_REFRESH = "refresh"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_TITLE = "title"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 2
    }
}

class AnalysisScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun analyze(playlistId: String, refreshTracks: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<AnalyzePlaylistWorker>()
            .setInputData(workDataOf(AnalyzePlaylistWorker.KEY_PLAYLIST to playlistId, AnalyzePlaylistWorker.KEY_REFRESH to refreshTracks))
            .build()
        workManager.enqueueUniqueWork(workName(playlistId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(playlistId: String) = workManager.cancelUniqueWork(workName(playlistId))

    fun state(playlistId: String): Flow<AnalysisUiState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(playlistId)).map { infos ->
            val info = infos.lastOrNull() ?: return@map AnalysisUiState.Idle
            when (info.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> AnalysisUiState.Running(
                    info.progress.getInt(AnalyzePlaylistWorker.KEY_DONE, 0),
                    info.progress.getInt(AnalyzePlaylistWorker.KEY_TOTAL, 0),
                    info.progress.getString(AnalyzePlaylistWorker.KEY_TITLE),
                )
                WorkInfo.State.SUCCEEDED -> AnalysisUiState.Done
                WorkInfo.State.FAILED -> AnalysisUiState.Failed(info.outputData.getString(AnalyzePlaylistWorker.KEY_ERROR) ?: "Falló el análisis")
                WorkInfo.State.CANCELLED -> AnalysisUiState.Idle
            }
        }

    private fun workName(playlistId: String) = "analyze-$playlistId"
}
