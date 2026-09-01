package com.schmmd.drumroll

import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.SyncState
import com.schmmd.drumroll.sync.PostResult
import com.schmmd.drumroll.sync.SheetsClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Runs the real client against a real socket. Exercises everything except Google
 * itself: the JSON that goes on the wire, and how each class of reply is sorted into
 * done / retry / give-up.
 *
 * Deliberately a bare ServerSocket rather than com.sun.net.httpserver, which is not on
 * the classpath for Android unit tests.
 */
class SheetsClientTest {

    private lateinit var server: StubServer

    @Before fun start() { server = StubServer().also { it.start() } }
    @After fun stop() = server.close()

    private val reading = Reading(
        id = "abc-123",
        readingDate = "2021-10-24",
        capturedAtUtc = 1_635_100_000_000L,
        value = 3_244_440L,
        meterId = "rockwell-t04",
        ocrRaw = null,
        minAgreement = 0.6f,
        corrected = true,
        photoPath = "/data/photo.jpg",
        note = "after irrigation",
        sync = SyncState.QUEUED,
    )

    @Test
    fun `sends the fields the sheet needs, and never the photo path`() = runBlocking {
        server.reply(200, """{"ok":true,"row":42}""")
        val result = SheetsClient(server.url(), "s3cret").post(reading)

        assertTrue("expected Ok but was $result", result is PostResult.Ok)
        val sent = JSONObject(server.lastBody())
        assertEquals("s3cret", sent.getString("secret"))
        assertEquals("abc-123", sent.getString("id"))
        assertEquals("2021-10-24", sent.getString("readingDate"))
        assertEquals(3_244_440L, sent.getLong("value"))   // painted zero included
        assertEquals("rockwell-t04", sent.getString("meterId"))
        assertTrue(sent.getBoolean("corrected"))
        assertEquals("after irrigation", sent.getString("note"))
        // The photo is a local audit trail; it must not leave the phone.
        assertFalse(sent.has("photoPath"))
    }

    @Test
    fun `a duplicate counts as success, which is what makes retrying safe`() = runBlocking {
        server.reply(200, """{"ok":true,"duplicate":true}""")
        assertTrue(SheetsClient(server.url(), "s").post(reading) is PostResult.Ok)
    }

    @Test
    fun `a server error is retryable`() = runBlocking {
        server.reply(503, "upstream unavailable")
        val result = SheetsClient(server.url(), "s").post(reading)
        assertTrue("expected Retry but was $result", result is PostResult.Retry)
    }

    @Test
    fun `a bad secret is permanent, not retried forever`() = runBlocking {
        server.reply(200, """{"ok":false,"error":"bad secret"}""")
        val result = SheetsClient(server.url(), "wrong").post(reading)
        assertTrue("expected Permanent but was $result", result is PostResult.Permanent)
        assertEquals("bad secret", (result as PostResult.Permanent).reason)
    }

    @Test
    fun `a 404 is permanent`() = runBlocking {
        server.reply(404, "no such deployment")
        assertTrue(SheetsClient(server.url(), "s").post(reading) is PostResult.Permanent)
    }

    @Test
    fun `an HTML sign-in page is retried rather than silently dropped`() = runBlocking {
        // What a misconfigured Apps Script deployment actually returns.
        server.reply(200, "<!DOCTYPE html><html>signin</html>")
        assertTrue(SheetsClient(server.url(), "s").post(reading) is PostResult.Retry)
    }

    @Test
    fun `an unreachable host is retryable, not a permanent failure`() = runBlocking {
        val result = SheetsClient("http://127.0.0.1:1/exec", "s").post(reading)
        assertTrue("expected Retry but was $result", result is PostResult.Retry)
    }

    @Test
    fun `no endpoint configured is permanent`() = runBlocking {
        assertTrue(SheetsClient("", "s").post(reading) is PostResult.Permanent)
    }
}

/** Minimal one-shot HTTP responder. Enough to satisfy HttpURLConnection, no more. */
private class StubServer {
    private val socket = ServerSocket(0)
    private var status = 200
    private var body = "{}"
    @Volatile private var received: String = ""

    fun url() = "http://127.0.0.1:${socket.localPort}/exec"
    fun reply(status: Int, body: String) { this.status = status; this.body = body }
    fun lastBody() = received

    fun start() = thread(isDaemon = true) {
        while (!socket.isClosed) {
            try {
                socket.accept().use { conn ->
                    val input = BufferedInputStream(conn.getInputStream())

                    // Headers, then exactly Content-Length bytes of body.
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b == -1) return@use
                        head.append(b.toChar())
                    }
                    val length = Regex("(?i)content-length:\\s*(\\d+)")
                        .find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(bytes, read, length - read)
                        if (n == -1) break
                        read += n
                    }
                    received = String(bytes, 0, read)

                    val payload = body.toByteArray()
                    conn.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${payload.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        write(payload)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // Socket closed during teardown, or a client hung up. Neither matters.
            }
        }
    }

    fun close() = socket.close()
}
