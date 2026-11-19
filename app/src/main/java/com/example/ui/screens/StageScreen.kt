package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.MediaCategory
import com.example.model.PhotoTransition
import com.example.model.PickedMediaItem
import com.example.model.StreamStatus
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.EmeraldLive
import com.example.viewmodel.CastUiState
import java.util.Locale

@Composable
fun StageScreen(
    uiState: CastUiState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Double) -> Unit,
    onSetVolume: (Int) -> Unit,
    onNextInQueue: () -> Unit,
    onPreviousInQueue: () -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onToggleSlideshow: () -> Unit,
    onUpdateTransition: (PhotoTransition) -> Unit,
    onStopCasting: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToCastHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = uiState.telemetry
    val currentItem = uiState.mediaQueue.getOrNull(uiState.currentQueueIndex)

    if (telemetry.status == StreamStatus.IDLE && !uiState.mirrorStats.isActive) {
        IdleStagePlaceholder(
            selectedReceiverName = uiState.selectedReceiver?.name,
            onDisconnect = onDisconnect,
            onNavigateToCastHub = onNavigateToCastHub,
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StageStatusHeader(
                category = telemetry.activeCategory ?: MediaCategory.SCREEN,
                status = telemetry.status,
                receiverName = uiState.selectedReceiver?.name ?: "Local Mirror Bridge",
                hasConnectedReceiver = uiState.selectedReceiver != null,
                errorMessage = telemetry.errorMessage,
                onDisconnect = onDisconnect
            )
        }

        if (telemetry.activeCategory == MediaCategory.SCREEN || uiState.mirrorStats.isActive) {
            item {
                ScreenMirrorMonitorSection(
                    uiState = uiState,
                    onStopMirror = onStopCasting
                )
            }
        } else if (telemetry.activeCategory == MediaCategory.PHOTOS) {
            item {
                PhotoStageSection(
                    uiState = uiState,
                    currentItem = currentItem,
                    onPrevious = onPreviousInQueue,
                    onNext = onNextInQueue,
                    onToggleSlideshow = onToggleSlideshow,
                    onSelectIndex = onSelectQueueIndex,
                    onUpdateTransition = onUpdateTransition,
                    onStop = onStopCasting
                )
            }
        } else {
            item {
                AudioVideoStageSection(
                    uiState = uiState,
                    currentItem = currentItem,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekTo = onSeekTo,
                    onSetVolume = onSetVolume,
                    onPrevious = onPreviousInQueue,
                    onNext = onNextInQueue,
                    onSelectIndex = onSelectQueueIndex,
                    onStop = onStopCasting
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun IdleStagePlaceholder(
    selectedReceiverName: String?,
    onDisconnect: () -> Unit,
    onNavigateToCastHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = "No active stream",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = "Stage Is Ready",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = selectedReceiverName?.let {
                        "Connected to $it. Choose Screen Mirror, Video, Photos, or Music from the Cast tab."
                    } ?: "Pick a Roku, plug-in streamer, or Smart TV in the Cast tab and enter its PIN to start streaming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedReceiverName != null) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("stage_idle_disconnect_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect")
                        }
                    }
                    Button(
                        onClick = onNavigateToCastHub,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("go_to_cast_hub_button")
                    ) {
                        Text("Open Cast Hub")
                    }
                }
            }
        }
    }
}

@Composable
private fun StageStatusHeader(
    category: MediaCategory,
    status: StreamStatus,
    receiverName: String,
    hasConnectedReceiver: Boolean,
    errorMessage: String?,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (status) {
                                    StreamStatus.STREAMING -> EmeraldLive
                                    StreamStatus.ERROR -> CrimsonStop
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                    Text(
                        text = "${category.label} • $receiverName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasConnectedReceiver) {
                    TextButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stage_disconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ScreenMirrorMonitorSection(
    uiState: CastUiState,
    onStopMirror: () -> Unit
) {
    val stats = uiState.mirrorStats
    val previewBitmap = remember(uiState.mirrorPreviewBytes) {
        uiState.mirrorPreviewBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = MaterialTheme.shapes.large,
            color = Color.Black,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Live screen mirror preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ScreenShare,
                            contentDescription = "Screen mirror active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Capturing Virtual Display...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryStatCard(
                label = "Frame Rate",
                value = "${stats.currentFps} fps",
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                label = "Bitrate",
                value = String.format(Locale.US, "%.1f Mbps", stats.bitrateMbps),
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                label = "Transferred",
                value = formatBytes(stats.totalBytesSent),
                modifier = Modifier.weight(1f)
            )
        }

        uiState.activeServerMirrorUrl?.let { mirrorUrl ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "LAN Browser Mirror Fallback URL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = mirrorUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Button(
            onClick = onStopMirror,
            colors = ButtonDefaults.buttonColors(
                containerColor = CrimsonStop,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("stop_mirror_button")
        ) {
            Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Screen Mirroring", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PhotoStageSection(
    uiState: CastUiState,
    currentItem: PickedMediaItem?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onUpdateTransition: (PhotoTransition) -> Unit,
    onStop: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            if (currentItem != null) {
                AsyncImage(
                    model = currentItem.uriString,
                    contentDescription = currentItem.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = "Photo preview",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentItem?.title ?: "Photo",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${uiState.currentQueueIndex + 1} of ${uiState.mediaQueue.size} photos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("photo_prev_button")
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous photo")
                }
                FilledIconButton(
                    onClick = onToggleSlideshow,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (uiState.slideshowEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("photo_slideshow_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Slideshow,
                        contentDescription = "Toggle slideshow"
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("photo_next_button")
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next photo")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "TV Transition",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(PhotoTransition.entries) { _, transition ->
                    FilterChip(
                        selected = uiState.spoofProfile.photoTransition == transition,
                        onClick = { onUpdateTransition(transition) },
                        label = { Text(transition.label) }
                    )
                }
            }
        }

        if (uiState.mediaQueue.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(uiState.mediaQueue) { index, item ->
                    val selected = index == uiState.currentQueueIndex
                    Surface(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelectIndex(index) },
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        AsyncImage(
                            model = item.uriString,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonStop, contentColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("stop_photo_cast_button")
        ) {
            Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Photo Casting")
        }
    }
}

@Composable
private fun AudioVideoStageSection(
    uiState: CastUiState,
    currentItem: PickedMediaItem?,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Double) -> Unit,
    onSetVolume: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onStop: () -> Unit
) {
    val telemetry = uiState.telemetry
    val isPaused = telemetry.status == StreamStatus.PAUSED
    val duration = telemetry.durationSec.coerceAtLeast(1.0)
    val pos = telemetry.positionSec.coerceIn(0.0, duration)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val artBitmap = remember(currentItem?.artworkBytes) {
                    currentItem?.artworkBytes?.let { bytes ->
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (artBitmap != null) {
                        Image(
                            bitmap = artBitmap,
                            contentDescription = currentItem?.title ?: "Album art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (telemetry.activeCategory == MediaCategory.MUSIC) {
                                Icons.Filled.MusicNote
                            } else {
                                Icons.Filled.Movie
                            },
                            contentDescription = "Media artwork",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = telemetry.currentTitle.ifEmpty { "Media Stream" },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = telemetry.currentSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = (pos / duration).toFloat().coerceIn(0f, 1f),
                        onValueChange = { fraction -> onSeekTo(fraction * duration) },
                        modifier = Modifier.testTag("playback_seek_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(pos.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDuration(duration.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = uiState.mediaQueue.size > 1,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous track",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        enabled = uiState.mediaQueue.size > 1,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next track",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Receiver volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = telemetry.volumePercent / 100f,
                        onValueChange = { onSetVolume((it * 100).toInt()) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("receiver_volume_slider")
                    )
                    Text(
                        text = "${telemetry.volumePercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (uiState.mediaQueue.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Up Next Queue",
                    style = MaterialTheme.typography.titleMedium
                )
                uiState.mediaQueue.forEachIndexed { idx, item ->
                    val isCurrent = idx == uiState.currentQueueIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelectIndex(idx) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonStop, contentColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("stop_media_cast_button")
        ) {
            Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Streaming")
        }
    }
}

@Composable
private fun TelemetryStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    }
}
