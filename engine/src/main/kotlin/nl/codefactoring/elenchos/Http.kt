// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A captured HTTP response: status, headers and raw body (parsing happens in the DSL). */
data class HttpResult(
    val method: String,
    val url: String,
    val status: Int,
    val requestBody: String?,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    /** First value of a response header, matched case-insensitively. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Minimal HTTP engine over the JDK's built-in client — no third-party HTTP
 * dependency. It adds `Accept: application/json`, and `Content-Type:
 * application/json` when there is a body; every other header comes from the
 * caller. Later headers win: [defaultHeaders] first, then the per-request ones.
 */
class HttpEngine(private val defaultHeaders: Map<String, String> = emptyMap()) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): HttpResult {
        val merged = LinkedHashMap<String, String>()
        fun put(name: String, value: String) {
            merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(merged::remove)
            merged[name] = value
        }
        put("Accept", "application/json")
        if (jsonBody != null) put("Content-Type", "application/json")
        for ((k, v) in defaultHeaders) put(k, v)
        for ((k, v) in headers) put(k, v)

        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        for ((k, v) in merged) b.header(k, v)
        val publisher =
            if (jsonBody != null) HttpRequest.BodyPublishers.ofString(jsonBody)
            else HttpRequest.BodyPublishers.noBody()
        b.method(method, publisher)

        val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(method, url, resp.statusCode(), jsonBody, resp.body(), resp.headers().map())
    }
}
