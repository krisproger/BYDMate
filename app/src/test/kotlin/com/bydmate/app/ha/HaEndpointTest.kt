package com.bydmate.app.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaEndpointTest {

    @Test
    fun `buildBaseUrl http`() {
        assertEquals("http://192.168.1.10:8123", HaEndpoint.buildBaseUrl("http", "192.168.1.10", 8123))
    }

    @Test
    fun `buildBaseUrl https custom port`() {
        assertEquals("https://host.local:8443", HaEndpoint.buildBaseUrl("https", "host.local", 8443))
    }

    @Test
    fun `buildBaseUrl blank host is null`() {
        assertNull(HaEndpoint.buildBaseUrl("http", "", 8123))
    }

    @Test
    fun `parseLegacyUrl full`() {
        val p = HaEndpoint.parseLegacyUrl("http://192.168.1.10:8123")
        assertEquals("http", p?.scheme)
        assertEquals("192.168.1.10", p?.host)
        assertEquals(8123, p?.port)
    }

    @Test
    fun `parseLegacyUrl https custom port`() {
        val p = HaEndpoint.parseLegacyUrl("https://host:8443")
        assertEquals("https", p?.scheme)
        assertEquals("host", p?.host)
        assertEquals(8443, p?.port)
    }

    @Test
    fun `parseLegacyUrl no scheme defaults http port 8123`() {
        val p = HaEndpoint.parseLegacyUrl("mwhome.local")
        assertEquals("http", p?.scheme)
        assertEquals("mwhome.local", p?.host)
        assertEquals(8123, p?.port)
    }

    @Test
    fun `parseLegacyUrl host port no scheme`() {
        val p = HaEndpoint.parseLegacyUrl("192.168.1.10:8123")
        assertEquals("http", p?.scheme)
        assertEquals("192.168.1.10", p?.host)
        assertEquals(8123, p?.port)
    }

    @Test
    fun `parseLegacyUrl blank or garbage is null`() {
        assertNull(HaEndpoint.parseLegacyUrl(""))
        assertNull(HaEndpoint.parseLegacyUrl("   "))
        assertNull(HaEndpoint.parseLegacyUrl("://nohost"))
    }
}