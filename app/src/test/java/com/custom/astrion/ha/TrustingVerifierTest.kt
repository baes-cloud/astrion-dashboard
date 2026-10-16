package com.custom.astrion.ha

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

class TrustingVerifierTest {
    private val session = mockk<SSLSession>(relaxed = true)
    private val strict = HostnameVerifier { _, _ -> false }

    @Test
    fun onlyListedHostsSkipTheNameCheck() {
        val v = trustingVerifier(strict) { setOf("10.0.0.20") }
        assertTrue(v.verify("10.0.0.20", session))
        assertFalse("other private IPs get the normal check", v.verify("10.0.0.21", session))
        assertFalse("plex.direct certificates are valid; no exemption", v.verify("1-2-3-4.abc.plex.direct", session))
    }

    @Test
    fun defaultVerdictStandsOtherwise() {
        val v = trustingVerifier(HostnameVerifier { _, _ -> true }) { emptySet() }
        assertTrue(v.verify("ha.example", session))
    }
}
