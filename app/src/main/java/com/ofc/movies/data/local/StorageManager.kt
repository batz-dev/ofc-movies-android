package com.ofc.movies.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ofc.movies.data.model.ContinueWatchingItem
import com.ofc.movies.data.model.MovieItem

data class DownloadedItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val sizeText: String,
    val quality: String,
    val downloadTimeMs: Long,
    val streamUrl: String,
    val downloadId: Long = -1L,
    val localUri: String = "",
    val status: String = "Ready",
    val movieId: String = "",
    val seriesName: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L
)

class StorageManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("ofc_storage_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        @Volatile
        private var instance: StorageManager? = null

        fun getInstance(context: Context): StorageManager {
            return instance ?: synchronized(this) {
                instance ?: StorageManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==========================================
    // 1. WATCHLIST (MY LIST)
    // ==========================================
    fun getWatchlist(): List<MovieItem> {
        val json = prefs.getString("watchlist", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MovieItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isInWatchlist(id: String): Boolean {
        return getWatchlist().any { it.id == id }
    }

    fun toggleWatchlist(movie: MovieItem): Boolean {
        val list = getWatchlist().toMutableList()
        val index = list.indexOfFirst { it.id == movie.id }
        val added: Boolean
        if (index >= 0) {
            list.removeAt(index)
            added = false
        } else {
            list.add(0, movie)
            added = true
        }
        prefs.edit().putString("watchlist", gson.toJson(list)).apply()
        return added
    }

    fun removeFromWatchlist(id: String) {
        val list = getWatchlist().toMutableList()
        list.removeAll { it.id == id }
        prefs.edit().putString("watchlist", gson.toJson(list)).apply()
    }

    // ==========================================
    // 2. DOWNLOADS (REAL PERSISTED STORE)
    // ==========================================
    fun getDownloads(): List<DownloadedItem> {
        val json = prefs.getString("downloads", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DownloadedItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isDownloaded(id: String): Boolean {
        return getDownloads().any { it.id == id }
    }

    fun addDownload(item: DownloadedItem) {
        val list = getDownloads().toMutableList()
        list.removeAll { it.id == item.id }
        list.add(0, item)
        prefs.edit().putString("downloads", gson.toJson(list)).apply()
    }

    fun removeDownload(id: String) {
        val list = getDownloads().toMutableList()
        list.removeAll { it.id == id }
        prefs.edit().putString("downloads", gson.toJson(list)).apply()
    }

    fun updateDownloadStatus(id: String, status: String, localUri: String? = null, sizeText: String? = null) {
        val list = getDownloads().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = list[index]
            list[index] = item.copy(
                status = status,
                localUri = localUri ?: item.localUri,
                sizeText = sizeText ?: item.sizeText
            )
            prefs.edit().putString("downloads", gson.toJson(list)).apply()
        }
    }

    fun updateDownloadProgress(id: String, status: String, bytesDownloaded: Long, totalBytes: Long) {
        val list = getDownloads().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = list[index]
            list[index] = item.copy(
                status = status,
                bytesDownloaded = bytesDownloaded,
                totalBytes = if (totalBytes > 0) totalBytes else item.totalBytes
            )
            prefs.edit().putString("downloads", gson.toJson(list)).apply()
        }
    }

    // ==========================================
    // 3. CONTINUE WATCHING (WATCH HISTORY)
    // ==========================================
    fun getContinueWatching(): List<ContinueWatchingItem> {
        val json = prefs.getString("continue_watching", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ContinueWatchingItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateContinueWatching(
        id: String,
        title: String,
        coverUrl: String,
        positionMs: Long,
        durationMs: Long,
        season: Int = 0,
        episode: Int = 0
    ) {
        if (durationMs <= 0) return
        val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val durationMins = (durationMs / 60000).toInt()
        val epText = if (season > 0 && episode > 0) "S${season}E${episode}" else null

        val list = getContinueWatching().toMutableList()
        val existing = list.firstOrNull { it.id == id }
        val resolvedCover = coverUrl.ifEmpty { existing?.coverUrl ?: "" }
        list.removeAll { it.id == id }

        // If watched more than 95%, don't clutter continue watching
        if (progress < 0.95f) {
            list.add(
                0,
                ContinueWatchingItem(
                    id = id,
                    title = title,
                    coverUrl = resolvedCover,
                    progress = progress,
                    durationMinutes = durationMins,
                    lastWatchedEpisode = epText
                )
            )
        }
        prefs.edit().putString("continue_watching", gson.toJson(list.take(20))).apply()
    }

    // ==========================================
    // 4. REAL SETTINGS
    // ==========================================
    fun getDefaultQuality(): String {
        return prefs.getString("setting_quality", "1080P Ultra HD") ?: "1080P Ultra HD"
    }

    fun setDefaultQuality(quality: String) {
        prefs.edit().putString("setting_quality", quality).apply()
    }

    fun isAutoplayEnabled(): Boolean {
        return prefs.getBoolean("setting_autoplay", true)
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("setting_autoplay", enabled).apply()
    }

    fun isFamilyModeEnabled(): Boolean {
        return prefs.getBoolean("setting_family_mode", false)
    }

    fun setFamilyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("setting_family_mode", enabled).apply()
    }

    // ==========================================
    // 5. SEARCH HISTORY
    // ==========================================
    fun getSearchHistory(): List<String> {
        val json = prefs.getString("search_history", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val list = getSearchHistory().toMutableList()
        list.removeAll { it.equals(trimmed, ignoreCase = true) }
        list.add(0, trimmed)
        prefs.edit().putString("search_history", gson.toJson(list.take(25))).apply()
    }

    fun removeSearchQuery(query: String) {
        val list = getSearchHistory().toMutableList()
        list.removeAll { it.equals(query.trim(), ignoreCase = true) }
        prefs.edit().putString("search_history", gson.toJson(list)).apply()
    }

    fun clearSearchHistory() {
        prefs.edit().remove("search_history").apply()
    }
}
