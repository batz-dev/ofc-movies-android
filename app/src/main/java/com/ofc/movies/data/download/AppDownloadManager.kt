package com.ofc.movies.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ofc.movies.data.local.DownloadedItem
import com.ofc.movies.data.local.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class DownloadTask(
    val id: String,
    val movieId: String,
    val title: String,
    val displayTitle: String,
    val coverUrl: String,
    val streamUrl: String,
    val quality: String,
    val sizeText: String,
    val signCookie: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val estimatedSizeBytes: Long = 0L
)

data class DownloadProgress(
    val taskId: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val percentage: Int = 0,
    val status: String = "Queued"
)

class AppDownloadManager private constructor(private val appContext: Context) {

    private val storageManager = StorageManager.getInstance(appContext)
    val taskQueue = ConcurrentLinkedQueue<DownloadTask>()
    private val allTasks = ConcurrentHashMap<String, DownloadTask>()
    private val cancelledTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val pausedTaskIds = ConcurrentHashMap.newKeySet<String>()

    private val _progressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, DownloadProgress>> = _progressMap.asStateFlow()

    @Volatile
    var currentRunningTaskId: String? = null

    companion object {
        @Volatile
        private var instance: AppDownloadManager? = null

        fun getInstance(context: Context): AppDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: AppDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun enqueueTasks(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return

        for (task in tasks) {
            cancelledTaskIds.remove(task.id)
            pausedTaskIds.remove(task.id)
            allTasks[task.id] = task

            val isSeries = task.season > 0 || task.episode > 0 || task.displayTitle.contains(" - S")
            storageManager.addDownload(
                DownloadedItem(
                    id = task.id,
                    title = task.displayTitle,
                    coverUrl = task.coverUrl,
                    sizeText = task.sizeText,
                    quality = task.quality,
                    downloadTimeMs = System.currentTimeMillis(),
                    streamUrl = task.streamUrl,
                    downloadId = -1L,
                    localUri = "",
                    status = "Queued",
                    movieId = task.movieId,
                    seriesName = if (isSeries) task.title else "",
                    season = task.season,
                    episode = task.episode,
                    bytesDownloaded = 0L,
                    totalBytes = task.estimatedSizeBytes
                )
            )

            updateProgress(
                task.id,
                DownloadProgress(
                    taskId = task.id,
                    bytesDownloaded = 0L,
                    totalBytes = task.estimatedSizeBytes,
                    percentage = 0,
                    status = "Queued"
                )
            )

            taskQueue.add(task)
        }

        triggerService()
    }

    fun triggerService() {
        try {
            val intent = Intent(appContext, DownloadService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Throwable) {
            android.util.Log.e("AppDownloadManager", "startForegroundService failed, falling back to startService", e)
            try {
                val intent = Intent(appContext, DownloadService::class.java)
                appContext.startService(intent)
            } catch (e2: Throwable) {
                android.util.Log.e("AppDownloadManager", "startService fallback also failed", e2)
            }
        }
    }

    fun pauseTask(taskId: String) {
        pausedTaskIds.add(taskId)
        taskQueue.removeIf { it.id == taskId }
        val currentProg = _progressMap.value[taskId]
        if (currentProg != null) {
            updateProgress(taskId, currentProg.copy(status = "Paused"))
            storageManager.updateDownloadProgress(
                taskId,
                "Paused",
                currentProg.bytesDownloaded,
                currentProg.totalBytes
            )
        } else {
            storageManager.updateDownloadStatus(taskId, "Paused")
        }
    }

    fun resumeTask(taskId: String) {
        pausedTaskIds.remove(taskId)
        cancelledTaskIds.remove(taskId)

        var task = allTasks[taskId]
        if (task == null) {
            val item = storageManager.getDownloads().firstOrNull { it.id == taskId }
            if (item != null) {
                task = DownloadTask(
                    id = item.id,
                    movieId = item.movieId.ifEmpty { item.id },
                    title = item.seriesName.ifEmpty { item.title },
                    displayTitle = item.title,
                    coverUrl = item.coverUrl,
                    streamUrl = item.streamUrl,
                    quality = item.quality,
                    sizeText = item.sizeText,
                    season = item.season,
                    episode = item.episode,
                    estimatedSizeBytes = item.totalBytes
                )
                allTasks[taskId] = task
            }
        }

        if (task != null) {
            val currentProg = _progressMap.value[taskId]
            val prevDownloaded = currentProg?.bytesDownloaded ?: 0L
            val prevTotal = currentProg?.totalBytes ?: task.estimatedSizeBytes
            val pct = if (prevTotal > 0) ((prevDownloaded * 100) / prevTotal).toInt().coerceIn(0, 99) else 0

            updateProgress(
                taskId,
                DownloadProgress(
                    taskId = taskId,
                    bytesDownloaded = prevDownloaded,
                    totalBytes = prevTotal,
                    percentage = pct,
                    status = "Queued"
                )
            )
            storageManager.updateDownloadStatus(taskId, "Queued")

            taskQueue.removeIf { it.id == taskId }
            taskQueue.add(task)
            triggerService()
        }
    }

    fun isPaused(taskId: String): Boolean = pausedTaskIds.contains(taskId)

    fun updateProgress(taskId: String, progress: DownloadProgress) {
        val current = _progressMap.value.toMutableMap()
        current[taskId] = progress
        _progressMap.value = current
    }

    fun removeProgress(taskId: String) {
        val current = _progressMap.value.toMutableMap()
        current.remove(taskId)
        _progressMap.value = current
    }

    fun cancelTask(taskId: String) {
        cancelledTaskIds.add(taskId)
        pausedTaskIds.remove(taskId)
        allTasks.remove(taskId)
        taskQueue.removeIf { it.id == taskId }
        removeProgress(taskId)
        storageManager.removeDownload(taskId)
    }

    fun isCancelled(taskId: String): Boolean = cancelledTaskIds.contains(taskId)

    fun clearCancelled(taskId: String) {
        cancelledTaskIds.remove(taskId)
    }
}
