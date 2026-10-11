package com.custom.astrion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Date formatting shared by the cards and overlays. java.time formatters are
 * immutable and thread-safe, so each pattern is built once and reused, where
 * helpers used to construct a fresh SimpleDateFormat (or three) per call.
 */
object Time {
    /** HA calendar attributes (`start_time` / `end_time`): local wall time. */
    private val haLocal: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    private val isoParser: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("uuuu-MM-dd'T'HH:mm")
        .optionalStart()
        .appendPattern(":ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter(Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    private const val MAX_CACHE_SIZE = 50
    private val cacheLock = Any()
    private val patterns = object : LinkedHashMap<Pair<String, Locale>, DateTimeFormatter>(
        MAX_CACHE_SIZE, 0.75f, true // access-order = true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Locale>, DateTimeFormatter>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private fun pattern(p: String, locale: Locale = Locale.getDefault()): DateTimeFormatter {
        val key = p to locale
        synchronized(cacheLock) {
            patterns[key]?.let { return it }
            val formatter = DateTimeFormatter.ofPattern(p, locale)
            patterns[key] = formatter
            return formatter
        }
    }

    // Visible for testing / stress testing
    internal fun cacheSize(): Int = synchronized(cacheLock) { patterns.size }
    internal fun clearCache() = synchronized(cacheLock) { patterns.clear() }
    internal fun hasPattern(p: String, locale: Locale = Locale.getDefault()): Boolean =
        synchronized(cacheLock) { patterns.containsKey(p to locale) }

    /** [ms] in the device's time zone with a SimpleDateFormat-style [pattern], e.g. "h:mm a". */
    fun format(ms: Long, pattern: String, locale: Locale = Locale.getDefault()): String =
        pattern(pattern, locale).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** HA's "2026-10-06 18:15:00" (local time) as epoch millis, or null. */
    fun parseHaLocal(s: String): Long? = runCatching {
        LocalDateTime.parse(s, haLocal).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /**
     * Parses an ISO date/time string (with or without offset, e.g. "2026-10-10T11:21:11+00:00"
     * or "2026-10-10T11:21:11" or "2026-10-10T11:21") as epoch millis, or null.
     */
    fun parseIsoDateTime(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching {
            val sanitized = if (s.contains(":60")) s.replace(":60", ":59") else s
            OffsetDateTime.parse(sanitized).toInstant().toEpochMilli()
        }.recoverCatching {
            val hasT = s.contains('T') || s.contains('t')
            if (!hasT) return@recoverCatching null
            val timePart = if (s.contains('T')) s.substringAfter('T') else s.substringAfter('t')
            if (timePart.isEmpty() || timePart.contains('Z') || timePart.contains('z') ||
                timePart.contains('+') || (timePart.lastIndexOf('-') > 0)
            ) {
                return@recoverCatching null
            }
            val sanitized = if (s.contains(":60")) s.replace(":60", ":59") else s
            LocalDateTime.parse(sanitized, isoParser).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** The local calendar day [ms] falls on. */
    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}
