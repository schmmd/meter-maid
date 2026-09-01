package com.schmmd.drumroll.sync

import com.schmmd.drumroll.data.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface PostResult {
    /** The row is in the sheet — whether we put it there now or on an earlier try. */
    data object Ok : PostResult
    /** Worth retrying: network, timeout, 5xx. */
    data class Retry(val reason: String) : PostResult
    /** Not worth retrying: bad secret, malformed endpoint. */
    data class Permanent(val reason: String) : PostResult
}

/**
 * Posts one reading to an Apps Script web app.
 *
 * The phone cannot tell "the write failed" from "the write succeeded and the reply was
 * lost", so it always retries and the script decides, keyed on the reading's UUID. A
 * duplicate response is success.
 */
class SheetsClient(private val endpoint: String, private val secret: String) {

    suspend fun post(reading: Reading): PostResult = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext PostResult.Permanent("No endpoint configured")

        val body = JSONObject().apply {
            put("secret", secret)
            put("id", reading.id)
            put("readingDate", reading.readingDate)
            put("value", reading.value)
            put("meterId", reading.meterId)
            put("corrected", reading.corrected)
            put("note", reading.note)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                // Apps Script redirects to a script.googleusercontent.com URL to serve
                // the response; without this the POST body is silently dropped.
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            when {
                code in 500..599 -> PostResult.Retry("HTTP $code")
                code !in 200..299 -> PostResult.Permanent("HTTP $code: ${text.take(120)}")
                else -> {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return@withContext PostResult.Retry("Unparseable reply")
                    if (json.optBoolean("ok")) PostResult.Ok
                    else PostResult.Permanent(json.optString("error", "rejected"))
                }
            }
        } catch (e: Exception) {
            PostResult.Retry(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
