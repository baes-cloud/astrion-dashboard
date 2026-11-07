package com.custom.astrion.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which speaker the volume and mute keys work, when it isn't the configured
 * one (the Club). Picked by touching a zone on the Zones tab (touching the Club card gives it back); null means "whatever the key's
 * config says". Lives outside both view models because the card picks it and
 * the key handler reads it.
 */
object VolumeFocus {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Pick [entityId] (touching a zone on the Zones tab). */
    fun select(entityId: String) {
        _selected.value = entityId
    }

    fun clear() {
        _selected.value = null
    }

    /** The entity a volume or mute key should act on, given the key's own. */
    fun target(configured: String?): String? = _selected.value ?: configured
}
