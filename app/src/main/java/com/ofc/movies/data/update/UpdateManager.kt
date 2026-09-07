package com.ofc.movies.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ofc.movies.BuildConfig
import com.ofc.movies.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/scaaa747-prog/ofc-movies-android/releases"
    private const val NOTIF_CHANNEL_ID = "app_updates"
    private const val NOTIF_ID = 9001

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()

    suspend fun checkForUpdate(currentVersion: String = BuildConfig.VERSION_NAME): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("User-Agent", "OFCMovies-App")
                    .header("Accept", "application/vnd.github+json")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val type = object : TypeToken<List<GitHubRelease>>() {}.type
                val releases: List<GitHubRelease> = gson.fromJson(body, type)

                for (release in releases) {
                    val apkAsset = release.assets?.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true)
                    } ?: continue

                    val releaseVersion = release.tagName.trimStart('v', 'V')
                    if (isNewerVersion(currentVersion, release.tagName)) {
                        return@withContext AppUpdateInfo(
                            versionName = releaseVersion,
                            releaseTitle = release.name ?: "Version $releaseVersion",
                            changelog = release.body?.takeIf { it.isNotBlank() }
                                ?: "Performance improvements and bug fixes.",
                            apkUrl = apkAsset.browserDownloadUrl,
                            apkSize = apkAsset.size,
                            isPrerelease = release.prerelease
                        )
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

    fun isNewerVersion(current: String, candidateTag: String): Boolean {
        val c = current.trimStart('v', 'V').trim()
        val t = candidateTag.trimStart('v', 'V').trim()
        if (c.equals(t, ignoreCase = true)) return false

        val cNums = c.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val tNums = t.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(cNums.size, tNums.size)
        for (i in 0 until maxLen) {
            val cn = cNums.getOrElse(i) { 0 }
            val tn = tNums.getOrElse(i) { 0 }
            if (tn > cn) return true
            if (tn < cn) return false
        }

        // Base version digits equal: if current is pre-release and candidate is stable
        if (c.contains("pre", ignoreCase = true) && !t.contains("pre", ignoreCase = true)) {
            return true
        }

        // If candidate is a newer pre-release tag (e.g. pre2 > pre1)
        if (t.contains("pre", ignoreCase = true) && c.contains("pre", ignoreCase = true)) {
            return t > c
        }

        return false
    }

    suspend fun downloadApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        try {
            createNotificationChannel(context)
        } catch (e: Exception) {}
        val notifManager = try {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (e: Exception) { null }

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val updatesDir = File(baseDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "OFC-Movies-${updateInfo.versionName}.apk")
        if (apkFile.exists()) apkFile.delete()

        val request = Request.Builder()
            .url(updateInfo.apkUrl)
            .header("User-Agent", "OFCMovies-App")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to download update: HTTP ${response.code}")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body from update server")
        val contentLength = if (body.contentLength() > 0) body.contentLength() else updateInfo.apkSize

        body.byteStream().use { input ->
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesReadTotal = 0L
                var read: Int
                var lastNotifTime = 0L
                var lastProgressTime = 0L

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesReadTotal += read

                    val percent = if (contentLength > 0) {
                        ((bytesReadTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                    } else 0

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 150 || percent == 100) {
                        lastProgressTime = now
                        withContext(Dispatchers.Main) {
                            onProgress(bytesReadTotal, contentLength, percent)
                        }
                    }

                    if (now - lastNotifTime > 600 || percent == 100) {
                        lastNotifTime = now
                        try {
                            notifManager?.let { showDownloadProgressNotification(context, it, updateInfo.versionName, percent) }
                        } catch (e: Exception) {}
                    }
                }
                output.flush()
            }
        }

        try {
            notifManager?.let { showDownloadCompleteNotification(context, it, updateInfo.versionName, apkFile) }
        } catch (e: Exception) {}

        apkFile
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        "Please allow OFC Movies to install app updates",
                        Toast.LENGTH_LONG
                    ).show()
                    val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context.applicationContext,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "Failed to launch installer", e)
            try {
                Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {}
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when updating OFC Movies"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun showDownloadProgressNotification(
        context: Context,
        nm: NotificationManager,
        version: String,
        percent: Int
    ) {
        try {
            val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_notif)
                .setContentTitle("Downloading OFC Movies v$version")
                .setContentText("Download in progress: $percent%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {}
    }

    private fun showDownloadCompleteNotification(
        context: Context,
        nm: NotificationManager,
        version: String,
        apkFile: File
    ) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, NOTIF_ID, installIntent, flags)

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notif)
            .setContentTitle("OFC Movies v$version Ready")
            .setContentText("Download complete! Tap to install update.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}
