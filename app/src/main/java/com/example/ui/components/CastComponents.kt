package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ConnectedTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AirPlayReceiver
import com.example.model.ConnectionStatus
import com.example.model.MirrorFpsCap
import com.example.model.MirrorResolution
import com.example.model.PlaybackTelemetry
import com.example.model.ReceiverType
import com.example.model.ReconnectPromptState
import com.example.model.SpoofProfile
import com.example.model.StreamStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.EmeraldLive

fun receiverIcon(type: ReceiverType): ImageVector = when (type) {
    ReceiverType.STREAMING_STICK -> Icons.Filled.SettingsInputHdmi
    ReceiverType.SMART_TV -> Icons.Outlined.ConnectedTv
}

@Composable
fun ReceiverCard(
    receiver: AirPlayReceiver,
    isSelected: Boolean,
    isProbing: Boolean,
    onSelect: () -> Unit,
    onConnectNoPin: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        label = "receiver_border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "receiver_bg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = !isSelected, onClick = onSelect)
            .testTag("receiver_card_${receiver.host}_${receiver.port}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        tonalElevation = if (isSelected) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = receiverIcon(receiver.type),
                        contentDescription = receiver.type.label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = receiver.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (receiver.protocolBadge.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = receiver.protocolBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = EmeraldLive.copy(alpha = 0.16f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = "Connected",
                                        tint = EmeraldLive,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = "Connected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldLive
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "PIN required on connect",
                                tint = AmberWarn,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${receiver.type.label} • ${receiver.host}:${receiver.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val dotColor = when {
                            isSelected -> EmeraldLive
                            receiver.isPaired -> AmberWarn.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = when {
                                isSelected && isProbing -> "Verifying PIN..."
                                isSelected -> "Connected • Active AirPlay session"
                                receiver.isPaired -> "Saved • PIN required to connect"
                                receiver.isManual -> "Saved IP • PIN required to connect"
                                receiver.capabilitiesSummary.isNotBlank() -> "${receiver.model} • PIN required"
                                else -> "Discovered • PIN required to connect"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("disconnect_receiver_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onSelect,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("connect_receiver_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect (PIN)", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onConnectNoPin,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("connect_no_pin_button")
                    ) {
                        Text("No PIN", style = MaterialTheme.typography.labelMedium)
                    }
                }

                TextButton(
                    onClick = onForget,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("forget_device_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Forget",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun MiniActiveCastBar(
    telemetry: PlaybackTelemetry,
    receiverName: String?,
    onOpenStage: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (telemetry.status == StreamStatus.IDLE) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpenStage)
            .testTag("mini_cast_bar"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(EmeraldLive)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = telemetry.currentTitle.ifEmpty { "Casting Active" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${telemetry.activeCategory?.label ?: "Stream"} • ${receiverName ?: "Local Bridge"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("mini_stop_cast_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop casting",
                    tint = CrimsonStop
                )
            }
        }
    }
}

@Composable
fun AddManualReceiverDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: String, type: ReceiverType, requiresPin: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }
    var selectedType by remember { mutableStateOf(ReceiverType.STREAMING_STICK) }
    var requiresPin by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Streamer or Smart TV",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter the local IP address of your Roku, plug-in streamer, or Smart TV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReceiverType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = {
                                selectedType = type
                                port = "7000"
                            },
                            label = {
                                Text(
                                    text = type.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.testTag("manual_type_${type.name.lowercase()}")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = requiresPin,
                        onClick = { requiresPin = true },
                        label = { Text("Has Screen (PIN)", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("manual_mode_pin")
                    )
                    FilterChip(
                        selected = !requiresPin,
                        onClick = { requiresPin = false },
                        label = { Text("Screenless (No PIN)", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("manual_mode_no_pin")
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device Name (optional)") },
                    placeholder = {
                        Text(
                            if (selectedType == ReceiverType.STREAMING_STICK) {
                                "Living Room Roku Stick"
                            } else {
                                "Living Room Smart TV"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_name_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP Address") },
                        placeholder = { Text("192.168.1.42") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .weight(2f)
                            .testTag("manual_host_input")
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_port_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, host, port, selectedType, requiresPin) },
                enabled = host.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_manual_button")
            ) {
                Text(if (requiresPin) "Connect (PIN)" else "Connect (No PIN)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AirPlayPairingDialog(
    receiver: AirPlayReceiver,
    isRequestingPin: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onRetryPin: () -> Unit,
    onConnectWithoutPin: () -> Unit,
    onSubmitPin: (String) -> Unit
) {
    var pin by remember(receiver.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Connect to ${receiver.name}",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter the 4-digit passcode shown on ${receiver.name} (${receiver.host}), or connect without a PIN if it is a screenless device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isRequestingPin) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Sending PIN prompt to device...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4) pin = it.filter(Char::isDigit) },
                    label = { Text("4-Digit PIN") },
                    placeholder = { Text("1234") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_code_input")
                )
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onRetryPin,
                        enabled = !isRequestingPin,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("resend_pin_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resend PIN")
                    }
                    TextButton(
                        onClick = onConnectWithoutPin,
                        enabled = !isRequestingPin,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("dialog_no_pin_button")
                    ) {
                        Text("No PIN (Screenless)")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitPin(pin) },
                enabled = pin.length == 4,
                modifier = Modifier.testTag("submit_pin_button")
            ) {
                Text("Verify & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DirectStreamUrlDialog(
    onDismiss: () -> Unit,
    onCastUrl: (url: String, title: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Cast Network Video URL",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Stream an MP4 or HLS (.m3u8) video link directly to your connected Roku, streamer, or Smart TV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Stream Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Video URL (MP4 / HLS)") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCastUrl(url, title) },
                enabled = url.trim().startsWith("http", ignoreCase = true),
                modifier = Modifier.testTag("confirm_stream_url_button")
            ) {
                Text("Stream")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun HeaderConnectionStatusPill(
    status: ConnectionStatus,
    reconnectAttempt: Int,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> EmeraldLive
            ConnectionStatus.CONNECTING -> AmberWarn
            ConnectionStatus.DISCONNECTED -> CrimsonStop
        },
        label = "header_status_dot_color"
    )
    val statusLabel = when {
        status == ConnectionStatus.CONNECTING && reconnectAttempt > 0 -> "Retry $reconnectAttempt/3"
        status == ConnectionStatus.CONNECTING -> "Connecting"
        status == ConnectionStatus.CONNECTED -> "Connected"
        else -> "Offline"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "header_dot_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = if (status == ConnectionStatus.DISCONNECTED) 0.96f else 0.9f,
        targetValue = if (status == ConnectionStatus.DISCONNECTED) 1.04f else 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (status == ConnectionStatus.CONNECTING) 650 else 1100,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "header_dot_scale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = if (status == ConnectionStatus.DISCONNECTED) 0.14f else 0.45f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (status == ConnectionStatus.CONNECTING) 650 else 1100,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "header_dot_halo"
    )

    Surface(
        modifier = modifier
            .semantics {
                contentDescription = "Connection status: $statusLabel"
            }
            .testTag("header_connection_status_pill"),
        shape = RoundedCornerShape(50),
        color = dotColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier.size(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .scale(pulseScale)
                        .alpha(haloAlpha)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .testTag("header_connection_status_dot")
                )
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = dotColor,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsMenuDialog(
    profile: SpoofProfile,
    isMirroringActive: Boolean,
    onSelectResolution: (MirrorResolution) -> Unit,
    onSelectFpsCap: (MirrorFpsCap) -> Unit,
    onOpenSpoofLab: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.HighQuality,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Mirror & Stream Settings",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Tune screen mirroring resolution and frame rate cap to match your Wi-Fi speed and TV hardware.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HighQuality,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Screen Mirror Resolution",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MirrorResolution.entries.forEach { res ->
                            FilterChip(
                                selected = profile.mirrorResolution == res,
                                onClick = { onSelectResolution(res) },
                                label = { Text(res.label) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_resolution_${res.shortLabel}")
                            )
                        }
                    }
                    if (isMirroringActive) {
                        Text(
                            text = "Resolution changes apply on the next mirror session.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarn
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Frame Rate Cap",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MirrorFpsCap.entries.forEach { cap ->
                            FilterChip(
                                selected = profile.mirrorFpsCap == cap,
                                onClick = { onSelectFpsCap(cap) },
                                label = { Text("${cap.fps} fps") },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_fps_${cap.fps}")
                            )
                        }
                    }
                    Text(
                        text = "Active cap: ${profile.mirrorFpsCap.label} • Target ${profile.mirrorProfileLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenSpoofLab()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("settings_open_spoof_lab_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apple Spoof & Logs Lab")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("settings_done_button")
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
fun ReconnectFailedPromptDialog(
    prompt: ReconnectPromptState,
    onRetry: () -> Unit,
    onEnterPin: () -> Unit,
    onDisconnect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDisconnect,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Connection lost",
                tint = AmberWarn,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Stream Disconnected",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "AirCast Bridge tried ${prompt.attemptsMade} times to re-establish your ${prompt.category.label.lowercase()} stream to ${prompt.receiver.name} (${prompt.receiver.host}), but the receiver did not respond.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = prompt.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("reconnect_prompt_retry_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retry (3x)")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onEnterPin,
                    modifier = Modifier.testTag("reconnect_prompt_pin_button")
                ) {
                    Text("Enter PIN")
                }
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("reconnect_prompt_disconnect_button")
                ) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
