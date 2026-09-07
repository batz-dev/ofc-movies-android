package com.ofc.movies.ui.screens

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ofc.movies.data.api.ApiClient
import com.ofc.movies.data.api.MovieBoxSigner
import com.ofc.movies.data.api.MovieRepository
import com.ofc.movies.data.download.AppDownloadManager
import com.ofc.movies.data.download.DownloadTask
import com.ofc.movies.data.local.DownloadedItem
import com.ofc.movies.data.local.StorageManager
import com.ofc.movies.data.model.*
import com.ofc.movies.ui.components.MovieCard
import com.ofc.movies.ui.components.ShimmerBox
import com.ofc.movies.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MovieDetailScreen(
    movieId: String,
    onBackClick: () -> Unit,
    onPlayClick: (movieId: String, title: String, se: Int, ep: Int) -> Unit = { _, _, _, _ -> },
    onRelatedMovieClick: (MovieItem) -> Unit = {},
    onGoToDownloads: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager.getInstance(context) }
    val repository = remember { MovieRepository(storageManager = storageManager) }
    val downloadManager = remember { AppDownloadManager.getInstance(context) }
    val inAppProgressMap by downloadManager.progressMap.collectAsState()
    val scope = rememberCoroutineScope()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    var movieDetail by remember { mutableStateOf<MovieDetailData?>(null) }
    var seasons by remember { mutableStateOf<List<SeasonItem>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var selectedDubId by remember { mutableStateOf(movieId) }
    var selectedSeason by remember { mutableIntStateOf(1) }
    var selectedEpisode by remember { mutableIntStateOf(1) }
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    var isInMyList by remember { mutableStateOf(storageManager.isInWatchlist(movieId)) }
    var isMovieDownloaded by remember { mutableStateOf(storageManager.isDownloaded(movieId)) }

    var showDownloadDialog by remember { mutableStateOf(false) }
    var showDownloadSuccessPopup by remember { mutableStateOf(false) }
    var downloadOptions by remember { mutableStateOf<List<DownloadQualityOption>>(emptyList()) }
    var isFetchingDownloadOptions by remember { mutableStateOf(false) }
    var selectedDownloadSeason by remember { mutableIntStateOf(1) }
    var selectedDownloadEpisodes by remember { mutableStateOf<Set<Int>>(setOf(1)) }
    var downloadDialogDubId by remember { mutableStateOf(movieId) }

    LaunchedEffect(showDownloadDialog, downloadDialogDubId, selectedDownloadSeason) {
        if (showDownloadDialog) {
            isFetchingDownloadOptions = true
            val playSeason = if (movieDetail?.subjectType == 2 && seasons.isNotEmpty()) selectedDownloadSeason else 0
            val playEpisode = if (movieDetail?.subjectType == 2 && seasons.isNotEmpty()) 1 else 0
            downloadOptions = repository.getDownloadOptions(
                subjectId = downloadDialogDubId,
                se = playSeason,
                ep = playEpisode,
                preloadedDetail = movieDetail
            )
            isFetchingDownloadOptions = false
        }
    }

    LaunchedEffect(movieId) {
        isLoading = true
        errorMsg = null
        selectedDubId = movieId
        isInMyList = storageManager.isInWatchlist(movieId)
        isMovieDownloaded = storageManager.isDownloaded(movieId)

        val detResult = repository.getMovieDetail(movieId)
        detResult.onSuccess { data ->
            movieDetail = data
            // Fetch seasons ONLY if TV Series (subjectType == 2)
            if (data.subjectType == 2) {
                val seasonsRes = repository.getSeasonInfo(movieId)
                seasonsRes.onSuccess { sList ->
                    val validSeasons = sList.filter { it.seasonNumber > 0 }
                    seasons = validSeasons
                    if (validSeasons.isNotEmpty()) {
                        selectedSeason = validSeasons.first().seasonNumber
                        selectedEpisode = 1
                    }
                }
            } else {
                seasons = emptyList()
                selectedSeason = 0
                selectedEpisode = 0
            }

            // Fetch recommendations
            val recResult = repository.getRecommendations(movieId)
            recResult.onSuccess { rList ->
                recommendations = rList
            }
        }.onFailure { err ->
            errorMsg = formatUserFriendlyError(err, "Failed to load movie details")
        }
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryRed)
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = errorMsg ?: "Error loading title", color = TextPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMsg = null
                                val detResult = repository.getMovieDetail(movieId)
                                detResult.onSuccess { data ->
                                    movieDetail = data
                                    if (data.subjectType == 2) {
                                        val seasonsRes = repository.getSeasonInfo(movieId)
                                        seasonsRes.onSuccess { sList ->
                                            val validSeasons = sList.filter { it.seasonNumber > 0 }
                                            seasons = validSeasons
                                            if (validSeasons.isNotEmpty()) {
                                                selectedSeason = validSeasons.first().seasonNumber
                                                selectedEpisode = 1
                                            }
                                        }
                                    } else {
                                        seasons = emptyList()
                                        selectedSeason = 0
                                        selectedEpisode = 0
                                    }
                                    val recResult = repository.getRecommendations(movieId)
                                    recResult.onSuccess { recommendations = it }
                                }.onFailure { err ->
                                    errorMsg = formatUserFriendlyError(err, "Failed to load movie details")
                                }
                                isLoading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                        shape = PillShape
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
            }
        } else {
            val detail = movieDetail ?: return@Box
            val context = LocalContext.current
            val coverUrl = ApiClient.getThumbnailUrl(detail.coverUrl, width = 720)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // 1. Parallax Backdrop Header
                item(key = "header_backdrop") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(coverUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = detail.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Top Gradient for Status Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(DarkBackground.copy(alpha = 0.8f), Color.Transparent)
                                    )
                                )
                        )

                        // Bottom Gradient Fade to DarkBackground
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            DarkBackground.copy(alpha = 0.7f),
                                            DarkBackground
                                        )
                                    )
                                )
                        )
                    }
                }

                // 2. Title, Badges, and Metadata
                item(key = "metadata") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            ),
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Gold Rating Badge
                            detail.rating?.let { r ->
                                if (r.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = RatingGold.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = "Rating",
                                                tint = RatingGold,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = r,
                                                color = RatingGold,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }

                            // Release Year
                            detail.releaseDate?.let { date ->
                                if (date.length >= 4) {
                                    Text(
                                        text = date.take(4),
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                            // Quality Badge (Accurate quality, no fake 4K)
                            val qualityText = when {
                                detail.isCamFilm -> "CAM"
                                else -> "HD"
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DarkSurfaceElevated
                            ) {
                                Text(
                                    text = qualityText,
                                    color = if (detail.isCamFilm) Color(0xFFFF9800) else TextSecondary,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }

                            // Audio Language Badge
                            val detailAudioLang = detail.audioLanguage
                            if (!detailAudioLang.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = PrimaryRed.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = detailAudioLang,
                                        color = PrimaryRed,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // Genres
                        detail.genre?.let { g ->
                            if (g.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = g,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // 3. Primary Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Play Button (Large Red Pill)
                            Button(
                                onClick = {
                                    val playSeason = if (detail.subjectType == 2 && seasons.isNotEmpty()) selectedSeason else 0
                                    val playEpisode = if (detail.subjectType == 2 && seasons.isNotEmpty()) selectedEpisode else 0
                                    storageManager.updateContinueWatching(
                                        id = movieId,
                                        title = detail.title,
                                        coverUrl = detail.coverUrl,
                                        positionMs = 1000L,
                                        durationMs = 7200000L,
                                        season = playSeason,
                                        episode = playEpisode
                                    )
                                    onPlayClick(
                                        selectedDubId,
                                        detail.title,
                                        playSeason,
                                        playEpisode
                                    )
                                },
                                shape = PillShape,
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Play Now",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            // Download Button (Real offline manager via Android DownloadManager)
                            IconButton(
                                onClick = {
                                    downloadDialogDubId = selectedDubId
                                    selectedDownloadSeason = if (seasons.isNotEmpty()) selectedSeason else 1
                                    selectedDownloadEpisodes = setOf(if (selectedEpisode > 0) selectedEpisode else 1)
                                    showDownloadDialog = true
                                },
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(DarkCard)
                            ) {
                                val activeDownload = inAppProgressMap.values.firstOrNull { prog ->
                                    (prog.taskId.startsWith(movieId) || (selectedDubId.isNotEmpty() && prog.taskId.startsWith(selectedDubId))) &&
                                    (prog.status == "Downloading" || prog.status == "Paused" || prog.status == "Queued")
                                }

                                val dlIcon = when {
                                    activeDownload?.status == "Downloading" -> Icons.Filled.CloudDownload
                                    activeDownload?.status == "Paused" -> Icons.Filled.Pause
                                    isMovieDownloaded -> Icons.Filled.Check
                                    else -> Icons.Filled.ArrowDownward
                                }
                                val dlTint = when {
                                    activeDownload?.status == "Downloading" -> PrimaryRed
                                    activeDownload?.status == "Paused" -> RatingGold
                                    isMovieDownloaded -> RatingGold
                                    else -> Color.White
                                }

                                Icon(
                                    imageVector = dlIcon,
                                    contentDescription = "Download",
                                    tint = dlTint
                                )
                            }

                            // My List Button (Real persistent watchlist)
                            IconButton(
                                onClick = {
                                    val item = MovieItem(
                                        id = movieId,
                                        title = detail.title,
                                        rating = detail.rating,
                                        year = detail.releaseDate,
                                        genre = detail.genre,
                                        description = detail.description,
                                        rawCover = detail.coverUrl
                                    )
                                    val added = storageManager.toggleWatchlist(item)
                                    isInMyList = added
                                    val msg = if (added) "Saved to My List" else "Removed from My List"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(DarkCard)
                            ) {
                                Icon(
                                    imageVector = if (isInMyList) Icons.Filled.Check else Icons.Filled.Add,
                                    contentDescription = "My List",
                                    tint = if (isInMyList) PrimaryRed else Color.White
                                )
                            }
                        }

                        // Live In-App Download Progress Banner
                        val activeDownloadForBanner = inAppProgressMap.values.firstOrNull { prog ->
                            (prog.taskId.startsWith(movieId) || (selectedDubId.isNotEmpty() && prog.taskId.startsWith(selectedDubId))) &&
                            (prog.status == "Downloading" || prog.status == "Paused" || prog.status == "Queued")
                        }

                        if (activeDownloadForBanner != null) {
                            val isPaused = activeDownloadForBanner.status == "Paused"
                            val isQueued = activeDownloadForBanner.status == "Queued"
                            val downloadedStr = formatDownloadSize(activeDownloadForBanner.bytesDownloaded, 0)
                            val totalStr = if (activeDownloadForBanner.totalBytes > 0) formatDownloadSize(activeDownloadForBanner.totalBytes, 0) else "Calculating..."
                            val pct = if (activeDownloadForBanner.percentage > 0) "${activeDownloadForBanner.percentage}%" else ""

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DarkCard,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (isPaused) Icons.Filled.Pause else if (isQueued) Icons.Filled.Schedule else Icons.Filled.CloudDownload,
                                                contentDescription = null,
                                                tint = if (isPaused) RatingGold else PrimaryRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            val titlePrefix = if (isPaused) "Download Paused" else if (isQueued) "Download Queued" else "Downloading"
                                            val pctLabel = if (pct.isNotEmpty()) " • $pct" else ""
                                            Text(
                                                text = "$titlePrefix$pctLabel",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = TextPrimary
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!isPaused && !isQueued) {
                                                IconButton(
                                                    onClick = { downloadManager.pauseTask(activeDownloadForBanner.taskId) },
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
                                                    onClick = { downloadManager.resumeTask(activeDownloadForBanner.taskId) },
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
                                                onClick = { downloadManager.cancelTask(activeDownloadForBanner.taskId) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Cancel",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "$downloadedStr / $totalStr",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = TextSecondary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    val progressFraction = if (activeDownloadForBanner.percentage > 0) {
                                        (activeDownloadForBanner.percentage.toFloat() / 100f).coerceIn(0f, 1f)
                                    } else if (activeDownloadForBanner.totalBytes > 0 && activeDownloadForBanner.bytesDownloaded > 0) {
                                        (activeDownloadForBanner.bytesDownloaded.toFloat() / activeDownloadForBanner.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else if (!isPaused && !isQueued) 0.05f else 0f

                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = if (isPaused) RatingGold else PrimaryRed,
                                        trackColor = DarkSurfaceElevated
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 4. Dubs / Language Selector (if available)
                        if (detail.dubs.isNotEmpty()) {
                            Text(
                                text = "Audio Dubs",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.dubs) { dub ->
                                    val isSelected = dub.subjectId == selectedDubId
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (isSelected) PrimaryRed else DarkCard,
                                        modifier = Modifier.clickable { selectedDubId = dub.subjectId }
                                    ) {
                                        Text(
                                            text = dub.lanName,
                                            color = if (isSelected) Color.White else TextSecondary,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        // 5. Seasons & Episodes Selector (for Series)
                        if (seasons.isNotEmpty()) {
                            Text(
                                text = "Seasons & Episodes",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Seasons Row
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(seasons) { s ->
                                    val isSelected = s.seasonNumber == selectedSeason
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (isSelected) PrimaryRed else DarkCard,
                                        modifier = Modifier.clickable {
                                            selectedSeason = s.seasonNumber
                                            selectedEpisode = 1
                                        }
                                    ) {
                                        Text(
                                            text = "Season ${s.seasonNumber}",
                                            color = if (isSelected) Color.White else TextSecondary,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Episodes Row
                            val currentSeason = seasons.firstOrNull { it.seasonNumber == selectedSeason } ?: seasons.first()
                            val maxEp = currentSeason.maxEpisode
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items((1..maxEp).toList()) { epNum ->
                                    val isSelected = epNum == selectedEpisode
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) PrimaryRed.copy(alpha = 0.25f) else DarkCard,
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryRed) else null,
                                        modifier = Modifier.clickable { selectedEpisode = epNum }
                                    ) {
                                        Text(
                                            text = "EP $epNum",
                                            color = if (isSelected) PrimaryRed else TextPrimary,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        // 6. Storyline Description (with expand / collapse)
                        detail.description?.let { desc ->
                            if (desc.isNotEmpty()) {
                                Text(
                                    text = "Storyline",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    lineHeight = 22.sp,
                                    maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .animateContentSize()
                                        .clickable { isDescriptionExpanded = !isDescriptionExpanded }
                                )
                                Text(
                                    text = if (isDescriptionExpanded) "Show Less" else "Read More",
                                    color = PrimaryRed,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .clickable { isDescriptionExpanded = !isDescriptionExpanded }
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }

                        // 7. Cast List
                        if (detail.cast.isNotEmpty()) {
                            Text(
                                text = "Cast & Crew",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.cast.take(10)) { person ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(72.dp)
                                    ) {
                                        val avatar = ApiClient.getThumbnailUrl(person.avatarUrl ?: "", width = 120)
                                        if (avatar.isNotEmpty()) {
                                            AsyncImage(
                                                model = avatar,
                                                contentDescription = person.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(DarkSurfaceElevated),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = person.name.take(1),
                                                    color = PrimaryRed,
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = person.name,
                                            color = TextPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        person.role?.let { r ->
                                            Text(
                                                text = r,
                                                color = TextSecondary,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // 8. Recommendations ("More Like This")
                        if (recommendations.isNotEmpty()) {
                            Text(
                                text = "More Like This",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(recommendations) { recMovie ->
                                    MovieCard(
                                        movie = recMovie,
                                        onClick = { onRelatedMovieClick(recMovie) },
                                        width = 110.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // DOWNLOAD QUALITY PICKER & SERIES MULTI-EPISODE DIALOG
        // ==========================================
        if (showDownloadDialog && movieDetail != null) {
            val detail = movieDetail!!
            val isSeries = detail.subjectType == 2 && seasons.isNotEmpty()
            val currentSeasonItem = seasons.firstOrNull { it.seasonNumber == selectedDownloadSeason } ?: seasons.firstOrNull()
            val maxEp = currentSeasonItem?.maxEpisode ?: 1

            AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                containerColor = DarkSurfaceElevated,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            tint = PrimaryRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isSeries) "Download Series" else "Download Movie",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 1. Audio Dubs Selector (if dubs exist)
                        if (detail.dubs.isNotEmpty()) {
                            Text(
                                text = "Audio Language",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.dubs) { dub ->
                                    val isSelected = dub.subjectId == downloadDialogDubId
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) PrimaryRed else DarkCard,
                                        modifier = Modifier.clickable { downloadDialogDubId = dub.subjectId }
                                    ) {
                                        Text(
                                            text = dub.lanName,
                                            color = if (isSelected) Color.White else TextSecondary,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // 2. TV Series Season & Episode Multi-Selector
                        if (isSeries) {
                            if (seasons.size > 1) {
                                Text(
                                    text = "Season",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(seasons) { s ->
                                        val isSelected = s.seasonNumber == selectedDownloadSeason
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (isSelected) PrimaryRed else DarkCard,
                                            modifier = Modifier.clickable {
                                                selectedDownloadSeason = s.seasonNumber
                                                selectedDownloadEpisodes = setOf(1)
                                            }
                                        ) {
                                            Text(
                                                text = "Season ${s.seasonNumber}",
                                                color = if (isSelected) Color.White else TextSecondary,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                ),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Episodes selection header with Select All / Deselect All
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Episodes (${selectedDownloadEpisodes.size} selected)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextSecondary
                                )
                                val allEpSet = (1..maxEp).toSet()
                                val isAllSelected = selectedDownloadEpisodes.containsAll(allEpSet)
                                Text(
                                    text = if (isAllSelected) "Deselect All" else "Select All ($maxEp)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PrimaryRed,
                                    modifier = Modifier
                                        .clickable {
                                            selectedDownloadEpisodes = if (isAllSelected) {
                                                setOf(1)
                                            } else {
                                                allEpSet
                                            }
                                        }
                                        .padding(4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Episode chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items((1..maxEp).toList()) { ep ->
                                    val isEpSelected = selectedDownloadEpisodes.contains(ep)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isEpSelected) PrimaryRed.copy(alpha = 0.25f) else DarkCard,
                                        border = if (isEpSelected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryRed) else null,
                                        modifier = Modifier.clickable {
                                            selectedDownloadEpisodes = if (isEpSelected) {
                                                if (selectedDownloadEpisodes.size > 1) {
                                                    selectedDownloadEpisodes - ep
                                                } else {
                                                    selectedDownloadEpisodes
                                                }
                                            } else {
                                                selectedDownloadEpisodes + ep
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = "EP $ep",
                                            color = if (isEpSelected) PrimaryRed else TextPrimary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // 3. Choose Quality Options
                        Text(
                            text = "Choose Quality",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isFetchingDownloadOptions) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = PrimaryRed,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Checking stream qualities...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        } else if (downloadOptions.isEmpty()) {
                            Text(
                                text = "No direct download qualities available for this audio track.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                downloadOptions.forEach { option ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = DarkCard,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    }
                                                }
                                                if (isSeries) {
                                                    showDownloadDialog = false
                                                    showDownloadSuccessPopup = true
                                                    val epsToDownload = selectedDownloadEpisodes.toList().sorted()
                                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                        try {
                                                            val tasks = mutableListOf<DownloadTask>()
                                                            for (ep in epsToDownload) {
                                                                val epOptions = repository.getDownloadOptions(
                                                                    subjectId = downloadDialogDubId,
                                                                    se = selectedDownloadSeason,
                                                                    ep = ep,
                                                                    preloadedDetail = movieDetail
                                                                )
                                                                val match = epOptions.firstOrNull { it.resolution == option.resolution }
                                                                    ?: epOptions.firstOrNull { it.resolution > 0 }
                                                                    ?: epOptions.firstOrNull()
                                                                if (match != null) {
                                                                    tasks.add(
                                                                        DownloadTask(
                                                                            id = "${downloadDialogDubId}_s${selectedDownloadSeason}_e${ep}",
                                                                            movieId = downloadDialogDubId,
                                                                            title = detail.title,
                                                                            displayTitle = "${detail.title} - S${selectedDownloadSeason}E${ep}",
                                                                            coverUrl = detail.coverUrl,
                                                                            streamUrl = match.streamUrl,
                                                                            quality = "${match.resolution}p",
                                                                            sizeText = match.sizeFormatted,
                                                                            signCookie = match.signCookie,
                                                                            season = selectedDownloadSeason,
                                                                            episode = ep,
                                                                            estimatedSizeBytes = match.sizeBytes
                                                                        )
                                                                    )
                                                                }
                                                            }
                                                            if (tasks.isNotEmpty()) {
                                                                downloadManager.enqueueTasks(tasks)
                                                            }
                                                        } catch (e: Throwable) {
                                                            android.util.Log.e("MovieDetailScreen", "Failed to queue series download", e)
                                                        }
                                                        isMovieDownloaded = true
                                                    }
                                                } else {
                                                    showDownloadDialog = false
                                                    showDownloadSuccessPopup = true
                                                    try {
                                                        val task = DownloadTask(
                                                            id = downloadDialogDubId,
                                                            movieId = downloadDialogDubId,
                                                            title = detail.title,
                                                            displayTitle = detail.title,
                                                            coverUrl = detail.coverUrl,
                                                            streamUrl = option.streamUrl,
                                                            quality = "${option.resolution}p",
                                                            sizeText = option.sizeFormatted,
                                                            signCookie = option.signCookie,
                                                            season = 0,
                                                            episode = 0,
                                                            estimatedSizeBytes = option.sizeBytes
                                                        )
                                                        downloadManager.enqueueTasks(listOf(task))
                                                    } catch (e: Throwable) {
                                                        android.util.Log.e("MovieDetailScreen", "Failed to queue movie download", e)
                                                    }
                                                    isMovieDownloaded = true
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = option.title,
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = TextPrimary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                val epCountText = if (isSeries && selectedDownloadEpisodes.size > 1) {
                                                    "${option.sizeFormatted} / ep (${selectedDownloadEpisodes.size} eps)"
                                                } else {
                                                    option.sizeFormatted
                                                }
                                                Text(
                                                    text = epCountText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = RatingGold
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Filled.ArrowDownward,
                                                contentDescription = "Download ${option.title}",
                                                tint = PrimaryRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDownloadDialog = false }) {
                        Text(text = "Close", color = TextSecondary)
                    }
                }
            )
        }

        // ==========================================
        // DOWNLOAD STARTED SUCCESS POPUP (WITH GO TO DOWNLOADS BUTTON)
        // ==========================================
        if (showDownloadSuccessPopup && movieDetail != null) {
            val detail = movieDetail!!
            val isSeries = detail.subjectType == 2 && seasons.isNotEmpty()

            AlertDialog(
                onDismissRequest = { showDownloadSuccessPopup = false },
                containerColor = DarkSurfaceElevated,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(38.dp)
                    )
                },
                title = {
                    Text(
                        text = "Download Started",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = if (isSeries) {
                            "Downloading ${selectedDownloadEpisodes.size} episode(s) one by one in the background. Video files will be saved in Downloads/ofcmovies."
                        } else {
                            "Downloading '${detail.title}' in the background. Video file will be saved in Downloads/ofcmovies."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDownloadSuccessPopup = false
                            onGoToDownloads()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                        shape = PillShape
                    ) {
                        Text(text = "Go to Downloads", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadSuccessPopup = false }) {
                        Text(text = "Dismiss", color = TextSecondary)
                    }
                }
            )
        }

        // Floating Back Button (Top Left)
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(DarkBackground.copy(alpha = 0.7f))
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

