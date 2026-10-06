package com.custom.astrion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Date formatting shared by the cards and overlays. java.time formatters are
 * immutable and thread-safe, so each pattern is built once and reused, where
 * helpers used to construct a fresh SimpleDateFormat (or three) per call.
 */
object Time {
    /** HA calendar attributes (`start_time` / `end_time`): local wall time. */
    private val haLocal: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val patterns = ConcurrentHashMap<String, DateTimeFormatter>()

    private fun pattern(p: String): DateTimeFormatter =
        patterns.getOrPut(p) { DateTimeFormatter.ofPattern(p, Locale.getDefault()) }

    /** [ms] in the device's time zone with a SimpleDateFormat-style [pattern], e.g. "h:mm a". */
    fun format(ms: Long, pattern: String): String =
        pattern(pattern).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** HA's "2026-10-06 18:15:00" (local time) as epoch millis, or null. */
    fun parseHaLocal(s: String): Long? = runCatching {
        LocalDateTime.parse(s, haLocal).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** The local calendar day [ms] falls on. */
    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}
