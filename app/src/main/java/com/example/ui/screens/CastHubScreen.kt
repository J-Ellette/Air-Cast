package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AirPlayReceiver
import com.example.model.MediaCategory
import com.example.ui.components.ReceiverCard
import com.example.ui.theme.CobaltSignal
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.CastUiState

@Composable
fun CastHubScreen(
    uiState: CastUiState,
    onRefreshScan: () -> Unit,
    onToggleScan: () -> Unit,
    onOpenAddManual: () -> Unit,
    onSelectReceiver: (AirPlayReceiver) -> Unit,
    onConnectNoPin: (AirPlayReceiver) -> Unit,
    onDisconnectReceiver: () -> Unit,
    onForgetDevice: (AirPlayReceiver) -> Unit,
    onOpenSpoofLab: () -> Unit,
    onStartScreenMirror: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenStreamUrl: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroSpoofStageCard(
                uiState = uiState,
                onOpenSpoofLab = onOpenSpoofLab,
                onDisconnect = onDisconnectReceiver,
                onForgetSelected = {
                    uiState.selectedReceiver?.let(onForgetDevice)
                }
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cast & Mirror Modes",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FilledTonalButton(
                        onClick = onOpenStreamUrl,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_stream_url_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = "Cast video URL",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stream URL", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CastModeTile(
                        title = "Mirror Screen",
                        subtitle = uiState.spoofProfile.mirrorProfileLabel,
                        icon = Icons.Filled.ScreenShare,
                        accentColor = ElectricCyan,
                        isActive = uiState.mirrorStats.isActive,
                        onClick = onStartScreenMirror,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_screen_mirror")
                    )
                    CastModeTile(
                        title = "Stream Video",
                        subtitle = "MP4, MOV, 4K",
                        icon = Icons.Filled.VideoLibrary,
                        accentColor = CobaltSignal,
                        isActive = uiState.telemetry.activeCategory == MediaCategory.VIDEO,
                        onClick = onPickVideo,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_video")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CastModeTile(
                        title = "Cast Photos",
                        subtitle = "Gallery & Slideshow",
                        icon = Icons.Filled.PhotoLibrary,
                        accentColor = VioletAccent,
                        isActive = uiState.telemetry.activeCategory == MediaCategory.PHOTOS,
                        onClick = onPickPhotos,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_photos")
                    )
                    CastModeTile(
                        title = "Play Music",
                        subtitle = "Audio & Playlists",
                        icon = Icons.Filled.MusicNote,
                        accentColor = EmeraldLive,
                        isActive = uiState.telemetry.activeCategory == MediaCategory.MUSIC,
                        onClick = onPickMusic,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_music")
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Roku, Streamers & Smart TVs",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = uiState.scanStatusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onToggleScan,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_nsd_scan_button")
                    ) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scanning", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Start NSD scan",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scan", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    IconButton(
                        onClick = onRefreshScan,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_scan_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Restart NSD scan",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenAddManual,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_manual_receiver_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add receiver by IP",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add IP", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (uiState.receivers.isEmpty()) {
            item {
                EmptyReceiverRadarCard(
                    wifiConnected = uiState.wifiConnected,
                    onRescan = onRefreshScan,
                    onAddManual = onOpenAddManual
                )
            }
        } else {
            items(uiState.receivers, key = { it.id }) { receiver ->
                val isSelected = uiState.selectedReceiver?.host == receiver.host &&
                    uiState.selectedReceiver.port == receiver.port
                ReceiverCard(
                    receiver = receiver,
                    isSelected = isSelected,
                    isProbing = isSelected && uiState.isProbingReceiver,
                    onSelect = { onSelectReceiver(receiver) },
                    onConnectNoPin = { onConnectNoPin(receiver) },
                    onDisconnect = onDisconnectReceiver,
                    onForget = { onForgetDevice(receiver) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun HeroSpoofStageCard(
    uiState: CastUiState,
    onOpenSpoofLab: () -> Unit,
    onDisconnect: () -> Unit,
    onForgetSelected: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 4.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_stage),
                contentDescription = "AirCast Studio backdrop",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC070B14),
                                Color(0xE60B1222)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.wifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            contentDescription = "Wi-Fi status",
                            tint = if (uiState.wifiConnected) EmeraldLive else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (uiState.localIpAddress != null && uiState.serverPort > 0) {
                                "LAN ${uiState.localIpAddress}:${uiState.serverPort}"
                            } else {
                                "Wi-Fi Required for LAN Cast"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpenSpoofLab)
                            .testTag("hero_spoof_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Smartphone,
                                contentDescription = "Spoofed Apple Identity",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${uiState.spoofProfile.clientName} • ${uiState.spoofProfile.protocolMode.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = uiState.selectedReceiver?.let { "Connected to ${it.name}" }
                            ?: "Select a Streamer or Smart TV",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiState.selectedReceiver?.let {
                            "Spoofing ${uiState.spoofProfile.modelIdentifier} (${uiState.spoofProfile.userAgent}) -> ${it.host}:${it.port}"
                        } ?: "Tap a Roku, plug-in streamer, or Smart TV below to connect and enter its 4-digit PIN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.selectedReceiver != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("hero_disconnect_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = onForgetSelected,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("hero_forget_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Forget Device",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CastModeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize(),
        shape = MaterialTheme.shapes.medium,
        color = if (isActive) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) accentColor else MaterialTheme.colorScheme.outline
        ),
        tonalElevation = if (isActive) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                AnimatedVisibility(visible = isActive) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(EmeraldLive)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyReceiverRadarCard(
    wifiConnected: Boolean,
    onRescan: () -> Unit,
    onAddManual: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_radar),
                contentDescription = "Receiver discovery radar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            Text(
                text = if (wifiConnected) "Scanning for Roku & Smart TVs" else "Connect to Wi-Fi Network",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Ensure your Roku, plug-in streamer, or Smart TV is on the same Wi-Fi subnet with AirPlay enabled, or add its LAN IP directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Again")
                }
                Button(
                    onClick = onAddManual,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Enter IP")
                }
            }
        }
    }
}
