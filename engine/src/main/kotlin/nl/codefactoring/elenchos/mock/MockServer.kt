// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A request the mock received. Header names are lower-cased. */
data class MockRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** What a mock route answers with. */
data class MockResponse(
    val status: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A tiny in-process HTTP server for trying out and testing probes without a
 * real target. It runs on the JDK's built-in server, listens on a free
 * loopback port, records every request, and answers from routes you script:
 *
 *     MockServer().use { mock ->
 *         mock.on("GET", "/notes/1") { MockResponse(200, """{"owner":"ada"}""") }
 *         val ctx = ProbeContext(mock.baseUrl)
 *         ...
 *     }
 *
 * A request with no matching route gets a 404.
 */
class MockServer(port: Int = 0) : AutoCloseable {

    private val routes = LinkedHashMap<Pair<String, String>, (MockRequest) -> MockResponse>()
    private val received = CopyOnWriteArrayList<MockRequest>()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    init {
        server.createContext("/") { handle(it) }
        server.start()
    }

    /** Base URL to use as the probe target, e.g. `http://127.0.0.1:54123`. */
    val baseUrl: String get() = "http://${server.address.address.hostAddress}:${server.address.port}"

    /** Every request received so far, in order. */
    val requests: List<MockRequest> get() = received.toList()

    /** Script the answer for [method] + exact [path] (query string ignored). Replaces an earlier route. */
    fun on(method: String, path: String, handler: (MockRequest) -> MockResponse): MockServer {
        synchronized(routes) { routes[method.uppercase() to path] = handler }
        return this
    }

    /** Shorthand for a fixed JSON answer. */
    fun on(method: String, path: String, status: Int, json: String): MockServer =
        on(method, path) { MockResponse(status, json) }

    private fun handle(ex: HttpExchange) {
        val req = MockRequest(
            method = ex.requestMethod.uppercase(),
            path = ex.requestURI.path,
            query = ex.requestURI.rawQuery,
            headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(", ") },
            body = ex.requestBody.readBytes().toString(Charsets.UTF_8),
        )
        received += req
        val handler = synchronized(routes) { routes[req.method to req.path] }
        val res = try {
            handler?.invoke(req) ?: MockResponse(404, """{"error":"no route for ${req.method} ${req.path}"}""")
        } catch (e: Exception) {
            MockResponse(500, """{"error":"mock handler failed"}""")
        }
        val bytes = res.body.toByteArray(Charsets.UTF_8)
        if (res.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            ex.responseHeaders.add("Content-Type", "application/json")
        }
        for ((k, v) in res.headers) ex.responseHeaders.add(k, v)
        if (bytes.isEmpty()) {
            ex.sendResponseHeaders(res.status, -1)
        } else {
            ex.sendResponseHeaders(res.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        ex.close()
    }

    override fun close() = server.stop(0)
}
