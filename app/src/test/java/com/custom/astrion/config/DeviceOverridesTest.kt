package com.custom.astrion.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** device.json carries each remote's identity over the shared dashboard.json. */
class DeviceOverridesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val shared = DashboardLoader.parse(File("../device/config/dashboard.json").readText())

    @Test
    fun sharedLayoutNamesNoRemote() {
        assertNull(shared.features.power.reportEntity)
        assertNull(shared.features.power.reportName)
    }

    @Test
    fun deviceFileSetsIdentityAndKeepsTheRestOfThePowerBlock() {
        val merged = DashboardLoader.withDeviceOverrides(shared, File("../device/config/device-113.json").readText())
        assertEquals("sensor.club_remote_113_battery", merged.features.power.reportEntity)
        assertEquals("Club remote 113", merged.features.power.reportName)
        assertEquals(shared.features.power.screenTimeoutSeconds, merged.features.power.screenTimeoutSeconds)
        assertEquals(shared.features.power.undockDebounceSeconds, merged.features.power.undockDebounceSeconds)
        assertEquals(shared.pages.size, merged.pages.size)
    }

    @Test
    fun eachRemoteHasItsOwnSensor() {
        val a = DashboardLoader.withDeviceOverrides(shared, File("../device/config/device-113.json").readText())
        val b = DashboardLoader.withDeviceOverrides(shared, File("../device/config/device-141.json").readText())
        assertEquals("sensor.club_remote_141_battery", b.features.power.reportEntity)
        assert(a.features.power.reportEntity != b.features.power.reportEntity)
    }

    @Test
    fun newBlocksAndScalarsReplace() {
        val merged = DashboardLoader.withDeviceOverrides(
            shared,
            """{ "voice": { "pipeline": "abc" }, "power": { "screen_timeout_seconds": -1 } }""",
        )
        assertEquals("abc", merged.features.voice.pipeline)
        assertEquals(-1, merged.features.power.screenTimeoutSeconds)
    }

    @Test(expected = Exception::class)
    fun nonObjectIsRejected() {
        DashboardLoader.withDeviceOverrides(shared, "[1, 2]")
    }

    @Test
    fun repositoryReloadsWhenEitherFileChanges() {
        val config = tmp.newFile("dashboard.json").apply { writeText("{}") }
        val device = File(tmp.root, "device.json")
        var loads = 0
        val repo = DefaultAppConfigRepository(
            configFileProvider = { config },
            deviceFileProvider = { device },
            loader = { loads++; DashboardLoader.Result(DashboardLoader.default, null) },
        )
        repo.loadNow()
        repo.loadNow()
        assertEquals("unchanged files are not re-read", 1, loads)

        device.writeText("{\"power\":{}}")
        repo.loadNow()
        assertEquals("a new device.json is picked up", 2, loads)

        repo.loadNow(force = true)
        assertEquals(3, loads)
    }
}
