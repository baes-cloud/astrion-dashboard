package com.custom.astrion.ha

/**
 * Where Home Assistant is and the token to use. Normally compiled in from
 * secrets.properties; a remote can override either in /sdcard/astrion/device.json:
 *
 *   { "ha": { "url": "http://192.0.2.10:8123", "token": "<long-lived access token>" } }
 *
 * Blank or missing values fall back to the compiled-in ones.
 */
data class HaEndpoint(val url: String, val token: String) {
    companion object {
        fun from(options: Map<String, Any?>, defaultUrl: String, defaultToken: String): HaEndpoint {
            val ha = options["ha"] as? Map<*, *>
            fun pick(key: String, default: String) =
                (ha?.get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: default
            return HaEndpoint(pick("url", defaultUrl), pick("token", defaultToken))
        }
    }
}
