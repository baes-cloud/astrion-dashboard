package com.custom.astrion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ir.IrModeOverlay
import com.custom.astrion.viewmodel.*
import com.custom.astrion.voice.VoiceOverlay
import com.custom.astrion.voice.VoicePhase

@Composable
fun DashboardScreen(
    dashboardVm: DashboardViewModel,
    powerVm: PowerViewModel,
    inputVm: InputViewModel,
    sheetHost: SheetHost = remember { SheetHost() },
    onRequestMicPermission: () -> Unit = {},
) {
    val dashboardState by dashboardVm.uiState.collectAsState()
    val powerState by powerVm.uiState.collectAsState()
    val inputState by inputVm.uiState.collectAsState()

    LaunchedEffect(powerState.isScreensaverOn) {
        if (powerState.isScreensaverOn && !inputState.isIrMode) {
            sheetHost.dismissAll()
        }
    }

    CompositionLocalProvider(LocalSheetHost provides sheetHost) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().unplacedWhen(powerState.isScreensaverOn)) {
                CompositionLocalProvider(
                    LocalDashboardShowing provides (powerState.isScreenOn && !powerState.isScreensaverOn)
                ) {
                    MinuteClock {
                        Dashboard(
                            client = dashboardVm.client,
                            connectionState = rememberUpdatedState(dashboardState.connectionState),
                            config = dashboardState.config,
                            configNotice = dashboardState.configNotice,
                            navTarget = dashboardState.navTarget,
                            onNavHandled = { dashboardVm.onIntent(DashboardIntent.NavTargetHandled) },
                        )
                        sheetHost.Host()
                    }
                }
            }

            if (powerState.isScreensaverOn) {
                Screensaver(
                    options = dashboardState.config.features.screensaver.raw,
                    entities = { dashboardVm.client.snapshot() },
                    client = dashboardVm.client,
                    connected = dashboardState.connectionState == ConnectionState.CONNECTED,
                    batteryPct = powerState.batteryPct,
                    charging = powerState.isCharging || powerState.isDocked,
                )
            }

            if (inputState.isIrMode) {
                IrModeOverlay(
                    options = dashboardState.config.features.ir.raw,
                    client = dashboardVm.client,
                    blaster = inputVm.irBlaster,
                    lastKeyLabel = inputState.irLastKey,
                    onClose = { inputVm.onIntent(InputIntent.CloseIrMode) },
                )
            }

            if (dashboardState.activeAlerts.isNotEmpty() && (dashboardState.activeAlarm == null || dashboardState.isAlarmHidden)) {
                val top = dashboardState.activeAlerts.first()
                AlertOverlay(
                    alert = top,
                    moreCount = dashboardState.activeAlerts.size - 1,
                    nowMs = System.currentTimeMillis(),
                    onAction = { dashboardVm.onIntent(DashboardIntent.TriggerAlertAction(it)) },
                    onHide = { dashboardVm.onIntent(DashboardIntent.DismissAlert(top.token)) },
                )
            }

            val ringing = dashboardState.activeAlarm
            if (ringing != null && !dashboardState.isAlarmHidden) {
                AlarmOverlay(
                    state = ringing,
                    onSnooze = { dashboardVm.onIntent(DashboardIntent.SnoozeAlarm) },
                    onDismiss = { dashboardVm.onIntent(DashboardIntent.DismissAlarm) },
                    onHide = { dashboardVm.onIntent(DashboardIntent.HideAlarm) },
                )
            }

            VolumePopup(
                client = dashboardVm.client,
                targetEntityId = inputState.lastVolumeEntityId,
                externalTriggerEpoch = inputState.volumeTriggerEpoch,
            )

            ActionToast(dashboardState.toastMessage, onGone = { dashboardVm.onIntent(DashboardIntent.ClearToast) })

            VoiceOverlay(
                state = dashboardState.voiceState,
                imageDir = dashboardState.config.features.voice.imageDir,
                onDismiss = {
                    if (dashboardState.voiceState.phase == VoicePhase.LISTENING) {
                        dashboardVm.onIntent(DashboardIntent.StopVoiceListening)
                    } else {
                        dashboardVm.onIntent(DashboardIntent.CancelVoice)
                    }
                },
            )
        }
    }
}
