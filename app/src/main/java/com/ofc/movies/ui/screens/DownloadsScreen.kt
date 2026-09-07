package com.ofc.movies.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ofc.movies.data.download.AppDownloadManager
import com.ofc.movies.data.local.DownloadedItem
import com.ofc.movies.data.local.StorageManager
import com.ofc.movies.data.model.formatDownloadSize
import com.ofc.movies.ui.components.DownloadNavIcon
import com.ofc.movies.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SeriesDownloadGroup(
    val seriesName: String,
    val seriesId: String,
    val coverUrl: String,
    val episodes: List<DownloadedItem>
)

private fun isSeriesItem(item: DownloadedItem): Boolean {
    return item.season > 0 || item.episode > 0 || item.seriesName.isNotEmpty() || item.title.contains(" - S")
}

private fun getSeriesTitle(item: DownloadedItem): String {
    if (item.seriesName.isNotEmpty()) return item.seriesName
    if (item.title.contains(" - S")) return item.title.substringBefore(" - S").trim()
    return item.title
}

private fun getSeriesId(item: DownloadedItem): String {
    if (item.movieId.isNotEmpty()) return item.movieId.substringBefore("_s")
    return item.id.substringBefore("_s")
}

private fun getEpisodeLabel(item: DownloadedItem): String {
    if (item.season > 0 && item.episode > 0) {
        return "Season ${item.season} • Episode ${item.episode}"
    }
    if (item.title.contains(" - S")) {
        return item.title.substringAfter(" - ").trim()
    }
    return item.title
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DownloadsScreen(
    onPlayOffline: (movieId: String, title: String) -> Unit,
    onBrowseMovies: () -> Unit = {},
    onNavigateToDetail: (movieId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storageManager = remember { StorageManager.getInstance(context) }
    val appDownloadManager = remember { AppDownloadManager.getInstance(context) }
    val inAppProgressMap by appDownloadManager.progressMap.collectAsState()

    var downloads by remember { mutableStateOf(storageManager.getDownloads()) }
    var itemToDelete by remember { mutableStateOf<DownloadedItem?>(null) }
    var selectedFilter by remember { mutableStateOf("All") }

    val expandedSeries = remember { mutableStateMapOf<String, Boolean>() }

    // Refresh downloads list only when a task starts, finishes, or changes status
    LaunchedEffect(inAppProgressMap.keys, inAppProgressMap.values.map { it.status }) {
        downloads = storageManager.getDownloads()
    }

    // Periodic safety sync (every 3 seconds)
    LaunchedEffect(Unit) {
        while (true) {
            val fresh = storageManager.getDownloads()
            if (fresh.size != downloads.size || fresh.map { it.status } != downloads.map { it.status }) {
                downloads = fresh
            }
            delay(3000)
        }
    }

    // Storage info via StatFs
    val (freeSpaceText, totalSpaceText, usedRatio) = remember(downloads.size) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            val used = total - available
            val freeGb = available.toDouble() / (1024 * 1024 * 1024)
            val totalGb = total.toDouble() / (1024 * 1024 * 1024)
            val ratio = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
            Triple("%.1f GB Free".format(freeGb), "%.1f GB".format(totalGb), ratio)
        } catch (e: Exception) {
            Triple("Available", "Device Storage", 0.3f)
        }
    }

    // Split Movies and Series
    val moviesList = remember(downloads) { downloads.filter { !isSeriesItem(it) } }
    val seriesList = remember(downloads) { downloads.filter { isSeriesItem(it) } }

    val seriesGroups = remember(seriesList) {
        seriesList
            .groupBy { getSeriesTitle(it) }
            .map { (name, episodes) ->
                val first = episodes.first()
                SeriesDownloadGroup(
                    seriesName = name,
                    seriesId = getSeriesId(first),
                    coverUrl = first.coverUrl,
                    episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
                )
            }
    }

    // Auto-expand new series groups
    LaunchedEffect(seriesGroups.map { it.seriesName }) {
        seriesGroups.forEach { group ->
            if (!expandedSeries.containsKey(group.seriesName)) {
                expandedSeries[group.seriesName] = true
            }
        }
    }

    val filterTabs = listOf(
        "All" to downloads.size,
        "Movies" to moviesList.size,
        "TV Shows" to seriesGroups.size
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    ),
                    color = TextPrimary
                )
                Text(
                    text = "${downloads.size} offline items available",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Storage Usage Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DarkCard,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Storage,
                            contentDescription = null,
                            tint = RatingGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Device Storage",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "$freeSpaceText of $totalSpaceText",
                        color = RatingGold,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = PrimaryRed,
                    trackColor = DarkSurfaceElevated
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Segmented Tabs Filter Pills
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filterTabs) { (tabTitle, count) ->
                val isSelected = selectedFilter == tabTitle
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) PrimaryRed else DarkCard,
                    modifier = Modifier.clickable { selectedFilter = tabTitle }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tabTitle,
                            color = if (isSelected) Color.White else TextPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (count > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) Color.White.copy(alpha = 0.25f) else DarkSurfaceElevated
                            ) {
                                Text(
                                    text = "$count",
                                    color = if (isSelected) Color.White else TextSecondary,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Main Downloads Content List
        val showMovies = selectedFilter == "All" || selectedFilter == "Movies"
        val showSeries = selectedFilter == "All" || selectedFilter == "TV Shows"

        val hasItemsToShow = (showMovies && moviesList.isNotEmpty()) || (showSeries && seriesGroups.isNotEmpty())

        if (!hasItemsToShow) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(DarkCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = if (selectedFilter == "TV Shows") "No TV Shows Downloaded"
                               else if (selectedFilter == "Movies") "No Movies Downloaded"
                               else "No Downloads Yet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Download movies and TV shows to watch them offline anytime without consuming mobile data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onBrowseMovies,
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                        modifier = Modifier.height(46.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Explore Movies & Shows",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Series Groups Section
                if (showSeries && seriesGroups.isNotEmpty()) {
                    items(seriesGroups, key = { "series_${it.seriesName}" }) { group ->
                        val isExpanded = expandedSeries[group.seriesName] ?: true

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Series Header Card
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedSeries[group.seriesName] = !isExpanded
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Poster
                                    AsyncImage(
                                        model = group.coverUrl,
                                        contentDescription = group.seriesName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(55.dp)
                                            .height(80.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkSurfaceElevated)
                                    )

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.seriesName,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = PrimaryRed.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "SERIES",
                                                    color = PrimaryRed,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${group.episodes.size} ${if (group.episodes.size == 1) "Episode" else "Episodes"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = RatingGold
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        val totalSizeText = group.episodes.map { it.sizeText }.firstOrNull() ?: ""
                                        Text(
                                            text = "Downloaded on device",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            expandedSeries[group.seriesName] = !isExpanded
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                                            tint = TextSecondary
                                        )
                                    }
                                }

                                // Episodes inside series
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically(tween(250)) + fadeIn(),
                                    exit = shrinkVertically(tween(250)) + fadeOut()
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        HorizontalDivider(
                                            color = DarkSurfaceElevated,
                                            thickness = 1.dp,
                                            modifier = Modifier.padding(horizontal = 14.dp)
                                        )

                                        group.episodes.forEach { epItem ->
                                            DownloadEpisodeItemRow(
                                                item = epItem,
                                                inAppProgress = inAppProgressMap[epItem.id],
                                                onPlay = { onPlayOffline(epItem.id, epItem.title) },
                                                onPause = { appDownloadManager.pauseTask(epItem.id) },
                                                onResume = { appDownloadManager.resumeTask(epItem.id) },
                                                onDelete = { itemToDelete = epItem }
                                            )
                                        }

                                        // "Download More Episodes" button
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            color = DarkSurfaceElevated
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        onNavigateToDetail(group.seriesId)
                                                    }
                                                    .padding(vertical = 12.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AddCircleOutline,
                                                    contentDescription = null,
                                                    tint = PrimaryRed,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Download More Episodes",
                                                    color = PrimaryRed,
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Standalone Movies Section
                if (showMovies && moviesList.isNotEmpty()) {
                    items(moviesList, key = { "movie_${it.id}" }) { movieItem ->
                        DownloadMovieCard(
                            item = movieItem,
                            inAppProgress = inAppProgressMap[movieItem.id],
                            onPlay = { onPlayOffline(movieItem.id, movieItem.title) },
                            onPause = { appDownloadManager.pauseTask(movieItem.id) },
                            onResume = { appDownloadManager.resumeTask(movieItem.id) },
                            onDelete = { itemToDelete = movieItem }
                        )
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        if (itemToDelete != null) {
            val target = itemToDelete!!
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(PrimaryRed.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = PrimaryRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "Delete Download?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete \"${target.title}\"? This will permanently remove the video and free up storage space on your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val toDelete = target
                            itemToDelete = null
                            scope.launch(Dispatchers.IO) {
                                appDownloadManager.cancelTask(toDelete.id)
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                                if (toDelete.downloadId > 0L) {
                                    try {
                                        dm?.remove(toDelete.downloadId)
                                    } catch (e: Exception) {}
                                }
                                if (toDelete.localUri.isNotEmpty()) {
                                    try {
                                        val uri = Uri.parse(toDelete.localUri)
                                        if (uri.scheme == "content") {
                                            context.contentResolver.delete(uri, null, null)
                                        } else if (uri.scheme == "file") {
                                            File(uri.path ?: "").delete()
                                        }
                                    } catch (e: Exception) {}
                                }
                                if (toDelete.streamUrl.isNotEmpty()) {
                                    try {
                                        com.ofc.movies.data.download.DownloadCacheManager.removeDashDownload(context, toDelete.streamUrl)
                                    } catch (e: Exception) {}
                                }
                                storageManager.removeDownload(toDelete.id)
                                withContext(Dispatchers.Main) {
                                    downloads = storageManager.getDownloads()
                                    Toast.makeText(context, "Deleted \"${toDelete.title}\"", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { itemToDelete = null },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = DarkCard
            )
        }
    }
}

@Composable
fun DownloadMovieCard(
    item: DownloadedItem,
    inAppProgress: com.ofc.movies.data.download.DownloadProgress?,
    onPlay: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onDelete: () -> Unit
) {
    val isDownloading = inAppProgress?.status == "Downloading" || item.status == "Downloading"
    val isPaused = inAppProgress?.status == "Paused" || item.status == "Paused"
    val isQueued = inAppProgress?.status == "Queued" || item.status == "Queued"
    val isFailed = item.status == "Failed"
    val isReady = item.status == "Ready" || (!isDownloading && !isPaused && !isQueued && !isFailed)

    val downloadedBytes = when {
        inAppProgress != null && inAppProgress.bytesDownloaded > 0 -> inAppProgress.bytesDownloaded
        item.bytesDownloaded > 0 -> item.bytesDownloaded
        else -> 0L
    }
    val totalBytes = when {
        inAppProgress != null && inAppProgress.totalBytes > 0 -> inAppProgress.totalBytes
        item.totalBytes > 0 -> item.totalBytes
        else -> 0L
    }
    val downloadedStr = formatDownloadSize(downloadedBytes, 0)
    val totalStr = if (totalBytes > 0) formatDownloadSize(totalBytes, 0) else item.sizeText
    val pct = when {
        inAppProgress != null && inAppProgress.percentage > 0 -> "${inAppProgress.percentage}%"
        totalBytes > 0 && downloadedBytes > 0 -> "${((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 99)}%"
        isDownloading -> "Downloading"
        isPaused -> "Paused"
        else -> ""
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(55.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceElevated)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = RatingGold.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = item.quality,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = RatingGold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    Text(text = "•", color = TextSecondary, style = MaterialTheme.typography.labelSmall)

                    if (isDownloading) {
                        Text(
                            text = "$downloadedStr / $totalStr ($pct)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryRed
                        )
                    } else if (isPaused) {
                        Text(
                            text = "Paused • $downloadedStr / $totalStr ($pct)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = RatingGold
                        )
                    } else if (isQueued) {
                        Text(
                            text = "Waiting in queue • $totalStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    } else if (isFailed) {
                        Text(
                            text = "Download Failed",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryRed
                        )
                    } else {
                        Text(
                            text = item.sizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(text = "•", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "Ready to Watch",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                if (isDownloading || isPaused || isQueued) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val progressFraction = if (totalBytes > 0 && downloadedBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 1f)
                    } else if (inAppProgress != null && inAppProgress.percentage > 0) {
                        (inAppProgress.percentage.toFloat() / 100f).coerceIn(0f, 1f)
                    } else if (isDownloading) 0.05f else 0f

                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isPaused) RatingGold else PrimaryRed,
                        trackColor = DarkSurfaceElevated
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Actions
            if (isDownloading) {
                IconButton(
                    onClick = onPause,
                    modifier = Modifier
                        .size(38.dp)
                        .background(DarkSurfaceElevated, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = RatingGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else if (isPaused) {
                IconButton(
                    onClick = onResume,
                    modifier = Modifier
                        .size(38.dp)
                        .background(PrimaryRed, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else if (isReady) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(40.dp)
                        .background(PrimaryRed, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DownloadEpisodeItemRow(
    item: DownloadedItem,
    inAppProgress: com.ofc.movies.data.download.DownloadProgress?,
    onPlay: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onDelete: () -> Unit
) {
    val isDownloading = inAppProgress?.status == "Downloading" || item.status == "Downloading"
    val isPaused = inAppProgress?.status == "Paused" || item.status == "Paused"
    val isQueued = inAppProgress?.status == "Queued" || item.status == "Queued"
    val isFailed = item.status == "Failed"
    val isReady = item.status == "Ready" || (!isDownloading && !isPaused && !isQueued && !isFailed)

    val downloadedBytes = when {
        inAppProgress != null && inAppProgress.bytesDownloaded > 0 -> inAppProgress.bytesDownloaded
        item.bytesDownloaded > 0 -> item.bytesDownloaded
        else -> 0L
    }
    val totalBytes = when {
        inAppProgress != null && inAppProgress.totalBytes > 0 -> inAppProgress.totalBytes
        item.totalBytes > 0 -> item.totalBytes
        else -> 0L
    }
    val downloadedStr = formatDownloadSize(downloadedBytes, 0)
    val totalStr = if (totalBytes > 0) formatDownloadSize(totalBytes, 0) else item.sizeText
    val pct = when {
        inAppProgress != null && inAppProgress.percentage > 0 -> "${inAppProgress.percentage}%"
        totalBytes > 0 && downloadedBytes > 0 -> "${((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 99)}%"
        isDownloading -> "Downloading"
        isPaused -> "Paused"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isReady) onPlay()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Episode Icon / Play Indicator
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isReady) PrimaryRed else DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                val circleProgress = if (inAppProgress != null && inAppProgress.percentage > 0) {
                    (inAppProgress.percentage.toFloat() / 100f).coerceIn(0.05f, 1f)
                } else 0.05f
                CircularProgressIndicator(
                    progress = { circleProgress },
                    modifier = Modifier.size(22.dp),
                    color = PrimaryRed,
                    strokeWidth = 2.5.dp,
                    trackColor = DarkCard
                )
            } else if (isPaused) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Paused",
                    tint = RatingGold,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isQueued) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Episode Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = getEpisodeLabel(item),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.quality,
                    style = MaterialTheme.typography.labelSmall,
                    color = RatingGold
                )
                Text(text = " • ", color = TextSecondary, style = MaterialTheme.typography.labelSmall)

                if (isDownloading) {
                    Text(
                        text = "$downloadedStr / $totalStr ($pct)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryRed
                    )
                } else if (isPaused) {
                    Text(
                        text = "Paused • $downloadedStr / $totalStr ($pct)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = RatingGold
                    )
                } else if (isQueued) {
                    Text(text = "Queued", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                } else if (isFailed) {
                    Text(text = "Failed", style = MaterialTheme.typography.labelSmall, color = PrimaryRed)
                } else {
                    Text(text = item.sizeText, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            if (isDownloading || isPaused) {
                Spacer(modifier = Modifier.height(6.dp))
                val progressFraction = if (totalBytes > 0 && downloadedBytes > 0) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 1f)
                } else if (inAppProgress != null && inAppProgress.percentage > 0) {
                    (inAppProgress.percentage.toFloat() / 100f).coerceIn(0f, 1f)
                } else if (isDownloading) 0.05f else 0f

                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (isPaused) RatingGold else PrimaryRed,
                    trackColor = DarkSurfaceElevated
                )
            }
        }

        if (isDownloading) {
            IconButton(
                onClick = onPause,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Pause",
                    tint = RatingGold,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else if (isPaused) {
            IconButton(
                onClick = onResume,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Resume",
                    tint = PrimaryRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
