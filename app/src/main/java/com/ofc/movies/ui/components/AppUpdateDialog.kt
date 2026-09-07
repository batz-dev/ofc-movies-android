package com.ofc.movies.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ofc.movies.data.update.AppUpdateInfo
import com.ofc.movies.data.update.UpdateManager
import com.ofc.movies.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDownloading by remember { mutableStateOf(false) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(updateInfo.apkSize) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(NetflixRed.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = "Update",
                        tint = NetflixRed,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "New Update Available!",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = NetflixRed.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "v${updateInfo.versionName}",
                            color = NetflixRed,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (updateInfo.apkSize > 0) {
                        val sizeMb = updateInfo.apkSize.toDouble() / (1024 * 1024)
                        Text(
                            text = "%.1f MB".format(sizeMb),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "What's New:",
                            color = RatingGold,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = updateInfo.changelog,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = NetflixRed,
                            trackColor = DarkSurfaceElevated
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val currentMb = downloadedBytes.toDouble() / (1024 * 1024)
                        val totalMb = totalBytes.toDouble() / (1024 * 1024)
                        val curStr = "%.1f".format(currentMb)
                        val totStr = if (totalMb > 0) "%.1f".format(totalMb) else "?"
                        Text(
                            text = "Downloading: $downloadPercent% ($curStr / $totStr MB)",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = NetflixRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isDownloading) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text("Later")
                        }
                    }

                    Button(
                        onClick = {
                            if (downloadedFile != null && downloadedFile?.exists() == true) {
                                UpdateManager.installApk(context.applicationContext, downloadedFile!!)
                            } else {
                                isDownloading = true
                                errorMessage = null
                                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    try {
                                        val file = UpdateManager.downloadApk(
                                            context = context.applicationContext,
                                            updateInfo = updateInfo,
                                            onProgress = { cur, tot, pct ->
                                                downloadedBytes = cur
                                                totalBytes = tot
                                                downloadPercent = pct
                                            }
                                        )
                                        downloadedFile = file
                                        isDownloading = false
                                        UpdateManager.installApk(context.applicationContext, file)
                                    } catch (e: Exception) {
                                        isDownloading = false
                                        errorMessage = "Download failed: ${e.localizedMessage ?: "Unknown error"}"
                                    }
                                }
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.weight(if (isDownloading) 2f else 1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NetflixRed,
                            contentColor = Color.White
                        )
                    ) {
                        if (isDownloading) {
                            Text("Downloading...")
                        } else if (downloadedFile != null) {
                            Text("Install Now")
                        } else {
                            Text("Update Now")
                        }
                    }
                }
            }
        }
    }
}
