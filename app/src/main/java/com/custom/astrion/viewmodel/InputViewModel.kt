package com.custom.astrion.viewmodel

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.input.KeyDispatcher
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.ui.StrongHaptics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class InputUiState(
    val isIrMode: Boolean = false,
    val irLastKey: String? = null,
    val isIrAvailable: Boolean = false,
    val irStatusLine: String = "",
    val irCodes: Map<HardwareKey, Long> = IrBlaster.DEFAULT_CODES,
    val irRepeat: Int = 1,
    val volumeTriggerEpoch: Long = 0L,
    val lastVolumeEntityId: String = "media_player.club",
)

sealed interface InputIntent {
    data class ProcessKeyEvent(val event: KeyEvent) : InputIntent
    data class ProcessTouchEvent(val event: MotionEvent) : InputIntent
    object ToggleIrMode : InputIntent
    object CloseIrMode : InputIntent
    data class BlastIr(val key: HardwareKey) : InputIntent
}

sealed interface InputEffect {
    data class NavigateToPage(val pageName: String) : InputEffect
    object TriggerVoiceKey : InputEffect
    object DismissTopSheet : InputEffect
    data class ShowToast(val message: String) : InputEffect
    data class LaunchPackage(val packageName: String) : InputEffect
    object OpenSystemSettings : InputEffect
}

class InputViewModel(
    private val context: Context,
    val router: HardwareKeyRouter,
    val irBlaster: IrBlaster,
    private val client: HaClient,
    private val configRepo: AppConfigRepository,
    private val haptics: StrongHaptics,
) : ViewModel() {

    companion object {
        private const val IR_TOGGLE_DEBOUNCE_MS = 500L
        val IR_INTERCEPTED = setOf(
            HardwareKey.UP, HardwareKey.DOWN, HardwareKey.LEFT, HardwareKey.RIGHT,
            HardwareKey.CENTER,
            HardwareKey.VOLUME_UP, HardwareKey.VOLUME_DOWN,
            HardwareKey.MUTE, HardwareKey.PAGE_UP, HardwareKey.PAGE_DOWN,
            HardwareKey.POWER, HardwareKey.HOME, HardwareKey.BACK,
        )
    }

    private val _uiState = MutableStateFlow(
        InputUiState(
            isIrAvailable = irBlaster.available,
            irStatusLine = irBlaster.statusLine(),
        )
    )
    val uiState: StateFlow<InputUiState> = _uiState.asStateFlow()

    private val _effects = Channel<InputEffect>(Channel.BUFFERED)
    val effects: Flow<InputEffect> = _effects.receiveAsFlow()

    val dispatcher = KeyDispatcher(
        router = router,
        scope = viewModelScope,
        onHoldFired = { haptics.tick() },
        onTapFired = { haptics.tick() },
    )

    private var lastIrToggleMs = 0L

    init {
        // Observe config for hotkeys and IR table updates
        viewModelScope.launch {
            configRepo.configFlow.collect { result ->
                val config = result.config
                bindHotkeys(config.hotkeys, config.longHotkeys, config.doubleHotkeys)
            }
        }
    }

    fun bindHotkeys(
        short: List<HotkeyConfig>,
        long: List<HotkeyConfig>,
        double: List<HotkeyConfig> = emptyList(),
    ) {
        router.clear()
        dispatcher.pageJumpKeys.clear()
        dispatcher.cancelPendingSingle()

        short.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull() ?: return@forEach
            router.on(key) { runHotkey(hk) }
            if (hk.page != null && hk.then.isEmpty()) dispatcher.pageJumpKeys += key
        }
        long.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull() ?: return@forEach
            router.onLong(key) { runHotkey(hk) }
        }
        double.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull() ?: return@forEach
            router.onDouble(key) { runHotkey(hk) }
        }

        val config = configRepo.getCurrentResult().config
        val irOpts = config.features.ir
        val codes = IrBlaster.fromConfig(irOpts.codes)
        val repeat = irOpts.repeat
        _uiState.update { it.copy(irCodes = codes, irRepeat = repeat) }

        bindIrToggle()
    }

    private fun bindIrToggle() {
        val config = configRepo.getCurrentResult().config
        val opts = config.features.ir
        val key = runCatching { HardwareKey.valueOf(opts.toggleKey.uppercase()) }.getOrNull() ?: return
        val toggle = {
            toggleIrMode()
            true
        }
        if (opts.toggleLong) router.onLong(key, toggle) else router.on(key, toggle)
    }

    fun toggleIrMode() {
        val now = SystemClock.uptimeMillis()
        if (now - lastIrToggleMs < IR_TOGGLE_DEBOUNCE_MS) return
        lastIrToggleMs = now
        val nextMode = !_uiState.value.isIrMode
        _uiState.update {
            it.copy(
                isIrMode = nextMode,
                irLastKey = null,
                isIrAvailable = irBlaster.available,
                irStatusLine = irBlaster.statusLine(),
            )
        }
        if (nextMode && !irBlaster.available) {
            viewModelScope.launch {
                _effects.send(InputEffect.ShowToast("No IR emitter available on this device"))
            }
        }
    }

    fun closeIrMode() {
        _uiState.update { it.copy(isIrMode = false) }
    }

    private fun runHotkey(hk: HotkeyConfig): Boolean {
        hk.page?.let { pageName ->
            viewModelScope.launch {
                _effects.send(InputEffect.DismissTopSheet)
                _effects.send(InputEffect.NavigateToPage(pageName))
            }
            return true
        }
        val ran = runAction(hk)
        hk.then.forEach { runAction(it) }
        return ran || hk.then.isNotEmpty()
    }

    private fun runAction(hk: HotkeyConfig): Boolean {
        val service = hk.service ?: return false
        val entityId = hk.entityId

        if (service == "astrion.toggle_mute" || service.contains("volume_") || service.contains("volume")) {
            _uiState.update {
                it.copy(
                    volumeTriggerEpoch = System.currentTimeMillis(),
                    lastVolumeEntityId = entityId ?: it.lastVolumeEntityId,
                )
            }
        }

        when (service) {
            "astrion.toggle_mute" -> {
                if (entityId == null) return false
                val muted = client.live[entityId]?.attrString("is_volume_muted") == "true"
                client.callService(
                    ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                )
                return true
            }
            "astrion.unjoin_others" -> {
                if (entityId == null) return false
                client.live[entityId]?.attrStringList("group_members")
                    ?.filter { it != entityId }
                    ?.forEach { client.callService(ServiceCall("media_player", "unjoin", entityId = it)) }
                return true
            }
            "astrion.open_settings" -> {
                viewModelScope.launch {
                    _effects.send(InputEffect.OpenSystemSettings)
                }
                return true
            }
            "astrion.launch" -> {
                val pkg = hk.data["package"] as? String ?: return false
                viewModelScope.launch {
                    _effects.send(InputEffect.LaunchPackage(pkg))
                }
                return true
            }
        }
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
        val quiet = domain == "remote" || domain == "androidtv" || svc.startsWith("volume_")
        client.callService(ServiceCall(domain, svc, entityId, data), confirm = !quiet)
        return true
    }

    fun handleKeyEvent(event: KeyEvent, isScreensaverOn: Boolean): Boolean {
        val code = event.keyCode
        val key = HardwareKey.fromKeyCode(code)

        // Wake screen and swallow key if screensaver is active
        if (isScreensaverOn) {
            return true
        }

        // IR Mode interception
        if (_uiState.value.isIrMode && key in IR_INTERCEPTED) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val codeHex = _uiState.value.irCodes[key]
                if (codeHex == null) {
                    _uiState.update { it.copy(irLastKey = "$key — no IR code mapped") }
                } else {
                    viewModelScope.launch {
                        val ok = irBlaster.blastAsync(codeHex, _uiState.value.irRepeat)
                        _uiState.update {
                            it.copy(
                                irLastKey = if (ok) "$key → 0x${codeHex.toString(16).uppercase()}"
                                else "$key — transmit FAILED"
                            )
                        }
                    }
                }
            }
            return true
        }

        // Voice button: toggle Assist session
        if (key == HardwareKey.VOICE && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            viewModelScope.launch {
                _effects.send(InputEffect.TriggerVoiceKey)
            }
            return true
        }

        if (!dispatcher.isBound(code)) {
            return false
        }

        return dispatcher.handle(code, event.action, event.repeatCount)
    }

    fun handleTouchEvent(ev: MotionEvent, isScreensaverOn: Boolean): Boolean {
        if (isScreensaverOn) {
            return true
        }
        return false
    }

    fun onIntent(intent: InputIntent) {
        when (intent) {
            is InputIntent.ProcessKeyEvent -> handleKeyEvent(intent.event, false)
            is InputIntent.ProcessTouchEvent -> handleTouchEvent(intent.event, false)
            InputIntent.ToggleIrMode -> toggleIrMode()
            InputIntent.CloseIrMode -> closeIrMode()
            is InputIntent.BlastIr -> {
                val codeHex = _uiState.value.irCodes[intent.key]
                if (codeHex != null) {
                    viewModelScope.launch {
                        val ok = irBlaster.blastAsync(codeHex, _uiState.value.irRepeat)
                        _uiState.update {
                            it.copy(
                                irLastKey = if (ok) "${intent.key} → 0x${codeHex.toString(16).uppercase()}"
                                else "${intent.key} — transmit FAILED"
                            )
                        }
                    }
                }
            }
        }
    }
}
