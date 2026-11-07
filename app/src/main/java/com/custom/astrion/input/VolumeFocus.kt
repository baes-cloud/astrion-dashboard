package com.custom.astrion.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which speaker the volume and mute keys work, when it isn't the configured
 * one (the Club). Picked on the Zones tab; null means "whatever the key's
 * config says". Lives outside both view models because the card picks it and
 * the key handler reads it.
 */
object VolumeFocus {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Pick [entityId], or give the keys back to the default if it was already picked. */
    fun toggle(entityId: String) {
        _selected.value = if (_selected.value == entityId) null else entityId
    }

    fun clear() {
        _selected.value = null
    }

    /** The entity a volume or mute key should act on, given the key's own. */
    fun target(configured: String?): String? = _selected.value ?: configured
}
