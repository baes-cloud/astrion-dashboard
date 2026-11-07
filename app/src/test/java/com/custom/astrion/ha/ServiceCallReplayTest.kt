package com.custom.astrion.ha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCallReplayTest {
    @Test
    fun absoluteStateCallsReplay() {
        assertTrue(ServiceCall("light", "turn_on", "light.kitchen").replaysSafely)
        assertTrue(ServiceCall("light", "turn_off", "light.kitchen").replaysSafely)
        assertTrue(ServiceCall("scene", "turn_on", "scene.santorini").replaysSafely)
        assertTrue(ServiceCall("lock", "lock", "lock.front").replaysSafely)
        assertTrue(ServiceCall("media_player", "select_source", "media_player.tv").replaysSafely)
        assertTrue(ServiceCall("media_player", "volume_set", "media_player.club").replaysSafely)
    }

    @Test
    fun stepsTogglesScriptsAndUnlockDoNot() {
        assertFalse(ServiceCall("light", "toggle", "light.kitchen").replaysSafely)
        assertFalse(ServiceCall("media_player", "volume_up", "media_player.club").replaysSafely)
        assertFalse(ServiceCall("media_player", "media_play_pause", "media_player.club").replaysSafely)
        assertFalse(ServiceCall("remote", "send_command", "remote.tv").replaysSafely)
        assertFalse(ServiceCall("androidtv", "adb_command", "media_player.tv").replaysSafely)
        assertFalse(ServiceCall("script", "turn_on", "script.long_lights").replaysSafely)
        assertFalse(ServiceCall("lock", "unlock", "lock.front").replaysSafely)
        assertFalse(ServiceCall("input_boolean", "toggle", "input_boolean.x").replaysSafely)
    }
}
