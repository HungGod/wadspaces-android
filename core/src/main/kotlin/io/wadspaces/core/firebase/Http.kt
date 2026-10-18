package io.wadspaces.core.firebase

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** How long it may take, in ms (a build's log is quick; a build start isn't). */
    val timeoutMs: Int = 30_000,
)

data class HttpResponse(val status: Int, val body: String)

/** A request out (HttpURLConnection on a phone; a fake in tests). */
fun interface Http {
    suspend fun send(r: HttpRequest): HttpResponse
}

/** The platform's HTTP: GET, POST, PUT and DELETE (no PATCH, so writes commit). */
class UrlHttp : Http {
    override suspend fun send(r: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val c = URI(r.url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = r.method
            c.connectTimeout = minOf(r.timeoutMs, 15_000)
            c.readTimeout = r.timeoutMs
            c.useCaches = false
            for ((k, v) in r.headers) c.setRequestProperty(k, v)
            if (r.body != null) {
                c.doOutput = true
                if (!r.headers.keys.any { it.equals("content-type", true) }) {
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                c.outputStream.use { it.write(r.body.toByteArray()) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            HttpResponse(status, stream?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}

/** A call that didn't work: its status (0: no answer at all) and why. */
class HttpFailure(val status: Int, message: String) : Exception(message)
