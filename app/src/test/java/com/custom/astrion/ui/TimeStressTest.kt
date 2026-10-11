package com.custom.astrion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TimeStressTest {

    @Test
    fun testConcurrentMultiThreadedFormattingAndParsing() {
        val threadCount = 32
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        val testEpoch = 1791633600000L // 2026-10-10 12:00:00 UTC

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        // 1. Formatting with various patterns & locales
                        val pattern = if (i % 2 == 0) "yyyy-MM-dd HH:mm:ss" else "h:mm a"
                        val locale = if (i % 3 == 0) Locale.US else Locale.FRANCE
                        val formatted = Time.format(testEpoch + i * 1000L, pattern, locale)
                        if (formatted.isEmpty()) {
                            errorCount.incrementAndGet()
                        }

                        // 2. Parsing HA local
                        val haStr = "2026-10-10 14:30:${String.format(Locale.US, "%02d", i % 60)}"
                        val parsedHa = Time.parseHaLocal(haStr)
                        if (parsedHa == null) {
                            errorCount.incrementAndGet()
                        }

                        // 3. Parsing ISO
                        val isoStr = "2026-10-10T12:00:${String.format(Locale.US, "%02d", i % 60)}+00:00"
                        val parsedIso = Time.parseIsoDateTime(isoStr)
                        if (parsedIso == null) {
                            errorCount.incrementAndGet()
                        }

                        // 4. Day conversion
                        val day = Time.day(testEpoch + i * 86400000L)
                        if (day.year < 2026) {
                            errorCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout waiting for threads", latch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Encountered errors during concurrent formatting/parsing", 0, errorCount.get())
    }

    @Test
    fun testInvalidDateStringsHandling() {
        // Syntax-invalid HA strings must return null
        val syntaxInvalidHaStrings = listOf(
            "",
            "   ",
            "not-a-date",
            "2026/10/10 12:00:00",
            "2026-13-10 12:00:00",
            "2026-10-10",
            "2026-10-10 25:00:00",
            "2026-10-10 12:60:00",
            "2026-10-10 12:00:61",
            "null",
            "undefined",
            "1234567890"
        )

        for (s in syntaxInvalidHaStrings) {
            val res = Time.parseHaLocal(s)
            assertNull("Expected null for invalid HA string '$s', but got $res", res)
        }

        // Syntax-invalid ISO strings must return null
        val invalidIsoStrings = listOf(
            "",
            "   ",
            "not-an-iso-date",
            "2026-10",
            "2026",
            "2026/10/10T12:00:00Z",
            "2026-13-10T12:00:00Z",
            "null",
            "{}",
            "true",
            "2026-10-10T"
        )

        for (s in invalidIsoStrings) {
            val res = Time.parseIsoDateTime(s)
            assertNull("Expected null for invalid ISO string '$s', but got $res", res)
        }
    }

    @Test
    fun testCalendarDateLeniencyVsStrictness() {
        // Strict calendar parsing: invalid calendar dates must return null
        val feb30HaResult = Time.parseHaLocal("2026-02-30 12:00:00")
        assertNull("Feb 30 is non-existent calendar date and must return null", feb30HaResult)

        val nonLeapHaResult = Time.parseHaLocal("2023-02-29 12:00:00")
        assertNull("Feb 29 on non-leap year 2023 must return null", nonLeapHaResult)

        // For parseIsoDateTime with offset: "2026-02-30T12:00:00Z"
        val feb30IsoResult = Time.parseIsoDateTime("2026-02-30T12:00:00Z")
        assertNull("Invalid date Feb 30 with offset must return null", feb30IsoResult)

        // For parseIsoDateTime without offset: "2026-02-30T12:00:00" and "2026-02-30T12:00"
        assertNull("Invalid date Feb 30 without offset must return null", Time.parseIsoDateTime("2026-02-30T12:00:00"))
        assertNull("Invalid date Feb 30 short ISO must return null", Time.parseIsoDateTime("2026-02-30T12:00"))

        // Malformed offset must return null
        assertNull("Malformed offset +99:99 must return null", Time.parseIsoDateTime("2026-10-10T12:00:00+99:99"))

        // Valid leap year date
        val leapDay2024 = "2024-02-29 12:00:00"
        val parsedLeap2024 = Time.parseHaLocal(leapDay2024)
        assertNotNull("2024-02-29 is valid leap day", parsedLeap2024)

        val leapDayIso2024 = "2024-02-29T12:00:00Z"
        val parsedLeapIso2024 = Time.parseIsoDateTime(leapDayIso2024)
        assertNotNull("2024-02-29T12:00:00Z is valid leap day ISO", parsedLeapIso2024)
    }

    @Test
    fun testLeapSecondsAndEdgeTimestamps() {
        // Leap second in ISO-8601: "2016-12-31T23:59:60Z"
        // OffsetDateTime.parse fails on second 60, but recoverCatching parses "2016-12-31T23:59"
        val leapSecIso = "2016-12-31T23:59:60Z"
        val parsedLeapIso = Time.parseIsoDateTime(leapSecIso)
        assertNotNull("Leap second string is gracefully recovered via fallback", parsedLeapIso)

        // Epoch 0 and negative epoch millis
        val epoch0Formatted = Time.format(0L, "yyyy-MM-dd", Locale.US)
        assertTrue(epoch0Formatted.startsWith("1970") || epoch0Formatted.startsWith("1969"))

        val negEpochFormatted = Time.format(-86400000L, "yyyy-MM-dd", Locale.US)
        assertTrue(negEpochFormatted.startsWith("1969"))

        // Far future date (year 2099)
        val zdt2099 = ZonedDateTime.of(2099, 12, 31, 23, 59, 59, 0, ZoneId.systemDefault())
        val ms2099 = zdt2099.toInstant().toEpochMilli()
        val formatted2099 = Time.format(ms2099, "yyyy-MM-dd", Locale.US)
        assertEquals("2099-12-31", formatted2099)
    }

    @Test
    fun testYearEndBoundaries() {
        val zdtOldYear = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000, ZoneId.systemDefault())
        val msOldYear = zdtOldYear.toInstant().toEpochMilli()
        val zdtNewYear = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault())
        val msNewYear = zdtNewYear.toInstant().toEpochMilli()

        assertEquals("2026-12-31 23:59:59", Time.format(msOldYear, "yyyy-MM-dd HH:mm:ss", Locale.US))
        assertEquals("2027-01-01 00:00:00", Time.format(msNewYear, "yyyy-MM-dd HH:mm:ss", Locale.US))

        assertEquals(LocalDate.of(2026, 12, 31), Time.day(msOldYear))
        assertEquals(LocalDate.of(2027, 1, 1), Time.day(msNewYear))
    }

    @Test
    fun testCacheEvictionSequentialBehavior() {
        Time.clearCache()

        val testEpoch = 1791633600000L
        // Sequential insertion of 120 unique patterns
        for (i in 0 until 120) {
            val pat = "yyyy-MM-dd 'SEQ_$i'"
            val res = Time.format(testEpoch, pat, Locale.US)
            assertTrue(res.contains("2026-10-10"))
        }

        // In purely sequential execution, size remains bounded at 50
        assertEquals("Sequential cache size should remain bounded at 50", 50, Time.cacheSize())
    }

    @Test
    fun testCacheEvictionConcurrentRaceConditionEmpirical() {
        Time.clearCache()

        val threadCount = 20
        val patternsPerThread = 15
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val testEpoch = 1791633600000L

        // Concurrently insert distinct patterns
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until patternsPerThread) {
                        val pat = "yyyy-MM-dd 'CONC_${t}_$i'"
                        Time.format(testEpoch, pat, Locale.US)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val observedSize = Time.cacheSize()
        println("Cache size after concurrent insertions is $observedSize (MAX_CACHE_SIZE is 50)")
        // Verify cache never exceeds MAX_CACHE_SIZE under concurrent insertion
        assertEquals("Cache size must remain bounded at 50 under concurrency", 50, observedSize)
    }

    @Test
    fun testLruEvictionOrderingEmpirical() {
        Time.clearCache()

        val testEpoch = 1791633600000L
        // Fill cache with 50 patterns: LRU_0 to LRU_49
        for (i in 0 until 50) {
            Time.format(testEpoch, "yyyy-MM-dd 'LRU_$i'", Locale.US)
        }
        assertEquals(50, Time.cacheSize())

        // The first inserted pattern is LRU_0
        val firstPattern = "yyyy-MM-dd 'LRU_0'"
        val secondPattern = "yyyy-MM-dd 'LRU_1'"

        // Frequently access firstPattern so it becomes the most recently used (MRU)
        for (i in 0 until 20) {
            Time.format(testEpoch, firstPattern, Locale.US)
        }

        // Insert a 51st pattern
        Time.format(testEpoch, "yyyy-MM-dd 'LRU_NEW_51'", Locale.US)

        // Cache size must remain bounded at 50
        assertEquals(50, Time.cacheSize())

        // In true LRU, the most recently used pattern (LRU_0) was accessed right before inserting 51,
        // so it must NOT be evicted
        assertTrue("Most recently used key must be retained in cache", Time.hasPattern(firstPattern, Locale.US))

        // The least recently used pattern (LRU_1) must have been evicted
        assertFalse("Least recently used key must be evicted from cache", Time.hasPattern(secondPattern, Locale.US))
    }

    @Test
    fun testHighConcurrencyBurst32ThreadsContinuousSampling() {
        Time.clearCache()

        val threadCount = 32
        val patternsPerThread = 50 // 32 * 50 = 1,600 unique patterns
        val executor = Executors.newFixedThreadPool(threadCount + 1)
        val latch = CountDownLatch(threadCount)
        val testEpoch = 1791633600000L
        val maxObservedSize = AtomicInteger(0)
        val stopSampling = java.util.concurrent.atomic.AtomicBoolean(false)

        // Continuous sampling thread
        executor.submit {
            while (!stopSampling.get()) {
                val s = Time.cacheSize()
                maxObservedSize.updateAndGet { current -> if (s > current) s else current }
                Thread.yield()
            }
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until patternsPerThread) {
                        val pat = "yyyy-MM-dd 'BURST_${t}_$i'"
                        Time.format(testEpoch, pat, Locale.US)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout waiting for 32 burst threads", latch.await(15, TimeUnit.SECONDS))
        stopSampling.set(true)
        executor.shutdown()

        val finalSize = Time.cacheSize()
        println("BURST EMPIRICAL: Max observed cache size during burst: ${maxObservedSize.get()}, Final size: $finalSize")
        assertTrue("Cache size must NEVER exceed 50 at any point (observed: ${maxObservedSize.get()})", maxObservedSize.get() <= 50)
        assertEquals("Final cache size must be exactly 50", 50, finalSize)
    }

    @Test
    fun testConcurrentLruAccessAndEvictionInterleaved() {
        Time.clearCache()
        val testEpoch = 1791633600000L

        // Initialize 50 patterns: ITEM_0 to ITEM_49
        for (i in 0 until 50) {
            Time.format(testEpoch, "yyyy-MM-dd 'ITEM_$i'", Locale.US)
        }
        assertEquals(50, Time.cacheSize())

        // Hot keys that will be read concurrently
        val hotKeys = listOf("yyyy-MM-dd 'ITEM_0'", "yyyy-MM-dd 'ITEM_1'", "yyyy-MM-dd 'ITEM_2'")
        // Cold keys that will NOT be read
        val coldKeys = listOf("yyyy-MM-dd 'ITEM_3'", "yyyy-MM-dd 'ITEM_4'", "yyyy-MM-dd 'ITEM_5'")

        val readers = 8
        val writers = 8
        val executor = Executors.newFixedThreadPool(readers + writers)
        val latch = CountDownLatch(readers + writers)
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        // Readers repeatedly access hot keys
        for (r in 0 until readers) {
            executor.submit {
                try {
                    while (!stopFlag.get()) {
                        for (key in hotKeys) {
                            Time.format(testEpoch, key, Locale.US)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Writers insert 200 new unique patterns
        for (w in 0 until writers) {
            executor.submit {
                try {
                    for (i in 0 until 25) {
                        Time.format(testEpoch, "yyyy-MM-dd 'NEW_${w}_$i'", Locale.US)
                        Thread.sleep(1)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait a short time for writers to finish
        Thread.sleep(500)
        stopFlag.set(true)
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Cache size must remain bounded at 50", 50, Time.cacheSize())

        // Verify all hot keys are still present
        for (hotKey in hotKeys) {
            assertTrue("Hot key $hotKey was heavily accessed and must be retained", Time.hasPattern(hotKey, Locale.US))
        }

        // Verify cold keys were evicted
        for (coldKey in coldKeys) {
            assertFalse("Cold key $coldKey was not accessed and must be evicted", Time.hasPattern(coldKey, Locale.US))
        }
    }

    @Test
    fun testCentennialLeapYearAndCalendarBoundaryPermutations() {
        // Centennial years: 2000 is leap, 1900 and 2100 are not
        assertNotNull("2000 is a centennial leap year", Time.parseHaLocal("2000-02-29 12:00:00"))
        assertNotNull("2000 is a centennial leap year (ISO)", Time.parseIsoDateTime("2000-02-29T12:00:00Z"))

        assertNull("1900 is NOT a leap year (divisible by 100, not 400)", Time.parseHaLocal("1900-02-29 12:00:00"))
        assertNull("1900 is NOT a leap year (ISO)", Time.parseIsoDateTime("1900-02-29T12:00:00Z"))

        assertNull("2100 is NOT a leap year (divisible by 100, not 400)", Time.parseHaLocal("2100-02-29 12:00:00"))
        assertNull("2100 is NOT a leap year (ISO)", Time.parseIsoDateTime("2100-02-29T12:00:00Z"))

        // 30-day months on day 31
        val thirtyDayMonths = listOf("04", "06", "09", "11")
        for (m in thirtyDayMonths) {
            assertNull("Month $m has 30 days; day 31 must return null", Time.parseHaLocal("2026-$m-31 12:00:00"))
            assertNull("Month $m has 30 days; day 31 must return null (ISO)", Time.parseIsoDateTime("2026-$m-31T12:00:00Z"))
            assertNull("Month $m has 30 days; day 31 must return null (ISO local)", Time.parseIsoDateTime("2026-$m-31T12:00:00"))
        }

        // Hour 24, minute 60, second 60 in haLocal
        assertNull("Hour 24 is invalid", Time.parseHaLocal("2026-10-10 24:00:00"))
        assertNull("Minute 60 is invalid", Time.parseHaLocal("2026-10-10 12:60:00"))
        assertNull("Second 60 in haLocal is invalid", Time.parseHaLocal("2026-10-10 12:00:60"))

        // ISO offset limits: Java ZoneOffset supports -18:00 to +18:00
        assertNotNull("Valid max positive offset +14:00", Time.parseIsoDateTime("2026-10-10T12:00:00+14:00"))
        assertNotNull("Valid max negative offset -12:00", Time.parseIsoDateTime("2026-10-10T12:00:00-12:00"))
        assertNotNull("Valid ZoneOffset within 18h limit +15:00", Time.parseIsoDateTime("2026-10-10T12:00:00+15:00"))
        assertNull("Invalid offset exceeding 18 hours +19:00", Time.parseIsoDateTime("2026-10-10T12:00:00+19:00"))
        assertNull("Invalid offset exceeding 18 hours -19:00", Time.parseIsoDateTime("2026-10-10T12:00:00-19:00"))
        assertNull("Malformed offset +99:99", Time.parseIsoDateTime("2026-10-10T12:00:00+99:99"))
    }
}
