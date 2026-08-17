package com.bydmate.app.ha

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaCommandPollerTest {

    private fun response(body: String, code: Int = 200): Response {
        val resp = mockk<Response>()
        every { resp.code } returns code
        every { resp.isSuccessful } returns (code in 200..299)
        every { resp.body } returns ResponseBody.create(null, body)
        return resp
    }

    private fun client(body: String, code: Int = 200): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns response(body, code)
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } returns call
        return http
    }

    private fun settings(): SettingsRepository {
        val s = mockk<SettingsRepository>()
        coEvery { s.getHaHost() } returns "192.168.1.10"
        coEvery { s.getHaPort() } returns 8123
        coEvery { s.isHaHttps() } returns false
        coEvery { s.getString(SettingsRepository.KEY_HA_CAR_NAME, any()) } returns "byd_car"
        coEvery { s.getString(SettingsRepository.KEY_HA_ENABLED, any()) } returns "true"
        return s
    }

    @Test
    fun `parse commands from poll response`() = runTest {
        val body = """{"status":"ok","car_name":"byd_car","commands":[
            {"id":"1","command":"ac_on","params":{"value":"on"}},
            {"id":"2","command":"sentry","params":{}}
        ]}"""

        val dispatcher = mockk<ActionDispatcher>()
        coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } returns DispatchResult(true)

        val poller = HaCommandPoller(
            httpClient = client(body),
            settingsRepository = settings(),
            actionDispatcher = dispatcher,
            sharedAdaptiveLoop = mockk(relaxed = true),
            context = mockk(relaxed = true),
        )
        val results = poller.processPollBody(body)
        assertEquals(2, results.size)
        assertEquals("ok", results[0].status)
        assertEquals("unsupported", results[1].status)
    }

    @Test
    fun `unknown car name keeps commands unacked`() = runTest {
        val body = """{"status":"ok","car_name":"byd_car","commands":[]}"""
        val poller = HaCommandPoller(
            httpClient = client(body),
            settingsRepository = settings(),
            actionDispatcher = mockk(relaxed = true),
            sharedAdaptiveLoop = mockk(relaxed = true),
            context = mockk(relaxed = true),
        )
        val results = poller.processPollBody(body)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `ack payload format`() {
        val poller = HaCommandPoller(
            httpClient = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            sharedAdaptiveLoop = mockk(relaxed = true),
            context = mockk(relaxed = true),
        )
        val json = poller.buildAckJson("id-1", "ok", "выполнено")
        assertTrue(json.contains("\"command_id\":\"id-1\""))
        assertTrue(json.contains("\"status\":\"ok\""))
        assertTrue(json.contains("\"message\":\"выполнено\""))
    }
}