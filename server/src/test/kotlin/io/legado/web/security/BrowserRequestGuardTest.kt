package io.legado.web.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserRequestGuardTest {
    @Test
    fun appliesBrowserProtocolPolicyWithoutChangingHostAllowlist() {
        NetworkGuard.configure { "127.0.0.1" }
        try {
            assertTrue(NetworkGuard.browserRequestAllowed("http://127.0.0.1/page"))
            assertTrue(NetworkGuard.browserRequestAllowed("wss://127.0.0.1/socket"))
            assertTrue(NetworkGuard.browserRequestAllowed("about:blank"))
            assertTrue(NetworkGuard.browserRequestAllowed("data:text/plain,example"))
            assertFalse(NetworkGuard.browserRequestAllowed("http://127.0.0.2/page"))
            assertFalse(NetworkGuard.browserRequestAllowed("https:/missing-host"))
            assertFalse(NetworkGuard.browserRequestAllowed("file:///example.txt"))
            assertFalse(NetworkGuard.browserRequestAllowed("ftp://127.0.0.1/example.txt"))
        } finally {
            NetworkGuard.resetForTests()
        }
    }
}
