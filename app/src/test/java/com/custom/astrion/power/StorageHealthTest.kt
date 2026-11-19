package com.custom.astrion.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthTest {
    @Test
    fun detectsReadOnlyDataMount() {
        val mounts = """
            rootfs / rootfs ro,seclabel 0 0
            /dev/block/mmcblk0p21 /system ext4 ro,relatime 0 0
            /dev/block/mmcblk0p23 /data f2fs ro,lazytime,nosuid,nodev,noatime,background_gc=off 0 0
        """.trimIndent()
        assertTrue(StorageHealth.parseDataReadOnly(mounts))
    }

    @Test
    fun detectsReadWriteDataMount() {
        val mounts = """
            /dev/block/mmcblk0p21 /system ext4 ro,relatime 0 0
            /dev/block/mmcblk0p23 /data f2fs rw,lazytime,nosuid,nodev,noatime,background_gc=on 0 0
        """.trimIndent()
        assertFalse(StorageHealth.parseDataReadOnly(mounts))
    }

    @Test
    fun ignoresDataSubpathsAndBlankInput() {
        val mounts = "/dev/block/foo /data/media sdcardfs ro,nosuid 0 0"
        assertFalse(StorageHealth.parseDataReadOnly(mounts))
        assertFalse(StorageHealth.parseDataReadOnly(""))
        assertFalse(StorageHealth.parseDataReadOnly(null))
    }
}
