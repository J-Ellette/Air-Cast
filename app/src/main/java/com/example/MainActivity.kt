package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.model.StreamStatus
import com.example.ui.components.AddManualReceiverDialog
import com.example.ui.components.AirPlayPairingDialog
import com.example.ui.components.DirectStreamUrlDialog
import com.example.ui.components.HeaderConnectionStatusPill
import com.example.ui.components.MiniActiveCastBar
import com.example.ui.components.ReconnectFailedPromptDialog
import com.example.ui.components.SettingsMenuDialog
import com.example.ui.screens.CastHubScreen
import com.example.ui.screens.SpoofLabScreen
import com.example.ui.screens.StageScreen
import com.example.ui.theme.AirCastBridgeTheme
import com.example.viewmodel.CastViewModel

object NavRoutes {
    const val CAST_HUB = "cast_hub"
    const val STAGE = "stage"
    const val SPOOF_LAB = "spoof_lab"
}

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirCastBridgeTheme(darkTheme = true) {
                AirCastApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirCastApp(
    viewModel: CastViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val protocolLogs by viewModel.protocolLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NavRoutes.CAST_HUB

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Screen Capture launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenMirroring(result.resultCode, result.data!!)
            navigateToTab(NavRoutes.STAGE)
        }
    }

    val launchScreenMirrorFlow: () -> Unit = {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // Contextual runtime notification permission (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with MediaProjection consent regardless so user isn't blocked if notifications are declined
        launchScreenMirrorFlow()
    }

    val requestScreenMirrorWithPermissions: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenMirrorFlow()
        }
    }

    // Zero-permission Photo Picker for Video
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.handlePickedVideo(uri)
            navigateToTab(NavRoutes.STAGE)
        }
    }

    // Zero-permission Photo Picker for Photos
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(12)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.handlePickedPhotos(uris)
            navigateToTab(NavRoutes.STAGE)
        }
    }

    // Zero-permission SAF Document Picker for Music/Audio
    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.handlePickedMusic(uris)
            navigateToTab(NavRoutes.STAGE)
        }
    }

    val destinations = remember {
        listOf(
            TopDestination(
                route = NavRoutes.CAST_HUB,
                labelRes = R.string.tab_cast,
                selectedIcon = Icons.Filled.Cast,
                unselectedIcon = Icons.Outlined.Cast,
                testTag = "nav_cast_hub"
            ),
            TopDestination(
                route = NavRoutes.STAGE,
                labelRes = R.string.tab_stage,
                selectedIcon = Icons.Filled.PlayCircle,
                unselectedIcon = Icons.Outlined.PlayCircle,
                testTag = "nav_stage"
            ),
            TopDestination(
                route = NavRoutes.SPOOF_LAB,
                labelRes = R.string.tab_spoof,
                selectedIcon = Icons.Filled.Tune,
                unselectedIcon = Icons.Outlined.Tune,
                testTag = "nav_spoof_lab"
            )
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        val isWideLayout = maxWidth >= 600.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        HeaderConnectionStatusPill(
                            status = uiState.connectionStatus,
                            reconnectAttempt = uiState.reconnectAttempt
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = viewModel::openSettingsDialog,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("header_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Mirroring & Stream Settings",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (!isWideLayout) {
                    Column {
                        if (currentRoute != NavRoutes.STAGE && uiState.telemetry.status != StreamStatus.IDLE) {
                            MiniActiveCastBar(
                                telemetry = uiState.telemetry,
                                receiverName = uiState.selectedReceiver?.name,
                                onOpenStage = { navigateToTab(NavRoutes.STAGE) },
                                onStop = viewModel::stopAllCasting
                            )
                        }
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        ) {
                            destinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                val label = stringResource(dest.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideLayout) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                    ) {
                        if (isWideLayout && currentRoute != NavRoutes.STAGE && uiState.telemetry.status != StreamStatus.IDLE) {
                            MiniActiveCastBar(
                                telemetry = uiState.telemetry,
                                receiverName = uiState.selectedReceiver?.name,
                                onOpenStage = { navigateToTab(NavRoutes.STAGE) },
                                onStop = viewModel::stopAllCasting
                            )
                        }

                        NavHost(
                            navController = navController,
                            startDestination = NavRoutes.CAST_HUB,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(NavRoutes.CAST_HUB) {
                                CastHubScreen(
                                    uiState = uiState,
                                    onRefreshScan = viewModel::refreshNetworkAndScan,
                                    onToggleScan = viewModel::toggleDiscovery,
                                    onOpenAddManual = viewModel::openAddManualDialog,
                                    onSelectReceiver = viewModel::selectReceiver,
                                    onConnectNoPin = viewModel::connectWithoutPin,
                                    onDisconnectReceiver = viewModel::disconnectReceiver,
                                    onForgetDevice = viewModel::forgetDevice,
                                    onOpenSpoofLab = { navigateToTab(NavRoutes.SPOOF_LAB) },
                                    onStartScreenMirror = {
                                        if (uiState.mirrorStats.isActive) {
                                            navigateToTab(NavRoutes.STAGE)
                                        } else {
                                            requestScreenMirrorWithPermissions()
                                        }
                                    },
                                    onPickVideo = {
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                    onOpenStreamUrl = viewModel::openStreamUrlDialog,
                                    onPickPhotos = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onPickMusic = {
                                        musicPickerLauncher.launch(arrayOf("audio/*"))
                                    }
                                )
                            }

                            composable(NavRoutes.STAGE) {
                                StageScreen(
                                    uiState = uiState,
                                    onTogglePlayPause = viewModel::togglePlayPause,
                                    onSeekTo = viewModel::seekToPosition,
                                    onSetVolume = viewModel::setVolume,
                                    onNextInQueue = viewModel::nextInQueue,
                                    onPreviousInQueue = viewModel::previousInQueue,
                                    onSelectQueueIndex = viewModel::selectQueueIndex,
                                    onToggleSlideshow = viewModel::toggleSlideshow,
                                    onUpdateTransition = viewModel::updatePhotoTransition,
                                    onStopCasting = viewModel::stopAllCasting,
                                    onDisconnect = viewModel::disconnectReceiver,
                                    onNavigateToCastHub = { navigateToTab(NavRoutes.CAST_HUB) }
                                )
                            }

                            composable(NavRoutes.SPOOF_LAB) {
                                SpoofLabScreen(
                                    profile = uiState.spoofProfile,
                                    protocolLogs = protocolLogs,
                                    pairedDevicesCount = uiState.pairedDevicesCount,
                                    onForgetAllDevices = viewModel::forgetAllDevices,
                                    onSelectPreset = viewModel::selectSpoofPreset,
                                    onUpdateClientName = viewModel::updateClientName,
                                    onUpdateUserAgent = viewModel::updateUserAgent,
                                    onUpdateProtocolMode = viewModel::updateProtocolMode,
                                    onUpdateMirrorQuality = viewModel::updateMirrorQuality,
                                    onRegenerateIds = viewModel::regenerateHardwareIdentifiers,
                                    onClearLogs = viewModel::clearProtocolLogs
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showAddManualDialog) {
        AddManualReceiverDialog(
            onDismiss = viewModel::closeAddManualDialog,
            onConfirm = viewModel::addManualReceiver
        )
    }

    uiState.pinDialogReceiver?.let { target ->
        AirPlayPairingDialog(
            receiver = target,
            isRequestingPin = uiState.isRequestingPin,
            errorText = uiState.pinDialogError,
            onDismiss = viewModel::dismissPinPairingDialog,
            onRetryPin = viewModel::retryPinPairing,
            onConnectWithoutPin = { viewModel.connectWithoutPin(target) },
            onSubmitPin = viewModel::submitPairingPin
        )
    }

    if (uiState.showStreamUrlDialog) {
        DirectStreamUrlDialog(
            onDismiss = viewModel::closeStreamUrlDialog,
            onCastUrl = { url, title ->
                viewModel.castDirectStreamUrl(url, title)
                navigateToTab(NavRoutes.STAGE)
            }
        )
    }

    if (uiState.showSettingsDialog) {
        SettingsMenuDialog(
            profile = uiState.spoofProfile,
            isMirroringActive = uiState.mirrorStats.isActive,
            onSelectResolution = viewModel::updateMirrorResolution,
            onSelectFpsCap = viewModel::updateMirrorFpsCap,
            onOpenSpoofLab = { navigateToTab(NavRoutes.SPOOF_LAB) },
            onDismiss = viewModel::closeSettingsDialog
        )
    }

    uiState.reconnectPrompt?.let { prompt ->
        ReconnectFailedPromptDialog(
            prompt = prompt,
            onRetry = viewModel::retryAfterReconnectPrompt,
            onEnterPin = viewModel::enterPinFromReconnectPrompt,
            onDisconnect = { viewModel.dismissReconnectPrompt(disconnect = true) }
        )
    }
}
