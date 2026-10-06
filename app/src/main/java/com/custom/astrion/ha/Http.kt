package com.custom.astrion.ha

import okhttp3.OkHttpClient

/**
 * The one OkHttp base every client in the app derives from with
 * `newBuilder()`, so they all share a single connection pool and dispatcher
 * thread pool instead of each starting their own.
 */
object Http {
    val base: OkHttpClient by lazy { OkHttpClient() }
}
