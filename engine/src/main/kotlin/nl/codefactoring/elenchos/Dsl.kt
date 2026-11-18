// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.model.Assertion
import nl.codefactoring.elenchos.model.HttpExchange
import nl.codefactoring.elenchos.model.Standard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Who a request is sent as: a display [name] for the evidence, the [headers]
 * that authenticate it (never written to evidence), and free-form
 * [attributes] a probe may want to carry along (an account id, a role, ...).
 * Create one with [ProbeScope.identity] or [ProbeScope.bearer].
 */
class Identity internal constructor(
    val name: String,
    internal val headers: Map<String, String>,
    val attributes: Map<String, String> = emptyMap(),
) {
    operator fun get(attribute: String): String? = attributes[attribute]
}

/** Thrown when a probe cannot be evaluated (→ ERROR, not RED). */
class ProbeError(message: String) : RuntimeException(message)

/** A probe definition: a named claim plus the body that reproduces it. */
class Probe(
    val id: String,
    val title: String,
    val body: ProbeScope.() -> Unit,
)

/** Entry point for the DSL. */
fun probe(id: String, title: String, body: ProbeScope.() -> Unit): Probe = Probe(id, title, body)

/**
 * The receiver for a probe body. Executes eagerly against the target, capturing
 * every exchange as evidence and every expectation as an [Assertion]. The engine
 * turns the accumulated assertions into GREEN/RED/ERROR.
 */
class ProbeScope internal constructor(val ctx: ProbeContext) {

    private val http = HttpEngine(ctx.defaultHeaders)

    internal val standards = mutableListOf<Standard>()
    internal val assertions = mutableListOf<Assertion>()
    internal val evidence = mutableListOf<HttpExchange>()

    private var lastResult: HttpResult? = null
    private var lastJsonParsed = false
    private var lastJson: JsonElement? = null

    // ---- variables / standards ----------------------------------------------

    /** Tag this finding with a requirement from any catalogue, e.g. standard("POLICY", "4.2"). */
    fun standard(framework: String, id: String) { standards += Standard(framework, id) }

    /** Read a required variable from the context (never hardcoded). */
    fun secret(name: String): String = ctx.require(name)

    /** Read an optional variable, falling back to [default]. */
    fun config(name: String, default: String): String = ctx.optional(name, default)

    // ---- identities ---------------------------------------------------------

    /** An identity that authenticates with arbitrary request [headers]. */
    fun identity(
        name: String,
        headers: Map<String, String> = emptyMap(),
        attributes: Map<String, String> = emptyMap(),
    ): Identity = Identity(name, headers, attributes)

    /** An identity that authenticates with `Authorization: Bearer <token>`. */
    fun bearer(name: String, token: String, attributes: Map<String, String> = emptyMap()): Identity =
        Identity(name, mapOf("Authorization" to "Bearer $token"), attributes)

    // ---- requests -----------------------------------------------------------

    fun get(path: String, `as`: Identity? = null, headers: Map<String, String> = emptyMap()) =
        request("GET", path, `as`, null, headers)

    fun post(
        path: String,
        `as`: Identity? = null,
        headers: Map<String, String> = emptyMap(),
        body: BodyScope.() -> Unit = {},
    ) = request("POST", path, `as`, BodyScope().apply(body).toJson(), headers)

    fun put(
        path: String,
        `as`: Identity? = null,
        headers: Map<String, String> = emptyMap(),
        body: BodyScope.() -> Unit = {},
    ) = request("PUT", path, `as`, BodyScope().apply(body).toJson(), headers)

    fun patch(
        path: String,
        `as`: Identity? = null,
        headers: Map<String, String> = emptyMap(),
        body: BodyScope.() -> Unit = {},
    ) = request("PATCH", path, `as`, BodyScope().apply(body).toJson(), headers)

    fun delete(path: String, `as`: Identity? = null, headers: Map<String, String> = emptyMap()) =
        request("DELETE", path, `as`, null, headers)

    /** General request with any method; body optional. */
    fun send(
        method: String,
        path: String,
        `as`: Identity? = null,
        headers: Map<String, String> = emptyMap(),
        body: (BodyScope.() -> Unit)? = null,
    ) = request(method, path, `as`, body?.let { BodyScope().apply(it).toJson() }, headers)

    private fun request(
        method: String,
        path: String,
        id: Identity?,
        jsonBody: String?,
        extraHeaders: Map<String, String>,
    ) {
        val url = ctx.baseUrl + path
        val res = http.send(method, url, headers = (id?.headers ?: emptyMap()) + extraHeaders, jsonBody = jsonBody)
        lastResult = res
        lastJsonParsed = false
        lastJson = null
        evidence += HttpExchange(
            step = "$method $path", method = method, url = url, identity = id?.name,
            requestBody = redactSecrets(jsonBody), status = res.status, responseBody = redactSecrets(res.body),
        )
    }

    /** Status of the most recent request (for preconditions). */
    val lastStatus: Int? get() = lastResult?.status

    // ---- capture ------------------------------------------------------------

    /**
     * The value at a JSON [path] in the last response, or null if absent. Use
     * it to carry a value into a later step (an id, a session token, ...).
     * Not an assertion, and the value is not added to the evidence.
     */
    fun capture(path: String): String? =
        when (val el = JsonPath.get(lastJsonElement(), path)) {
            null, is JsonNull -> null
            else -> JsonPath.render(el)
        }

    /** Like [capture], but a missing value makes the finding ERROR (could not evaluate). */
    fun captureRequired(path: String): String =
        capture(path) ?: throw ProbeError("capture failed: no value at '$path' (HTTP $lastStatus)")

    /** A header of the last response, or null. */
    fun captureHeader(name: String): String? = lastResult?.header(name)

    /**
     * Fetch one JSON object as a reusable map (the first element if the body is
     * an array, or the object itself). Used to snapshot a resource before a
     * destructive step so the probe can restore it afterwards. Non-assertion.
     */
    fun fetchRow(path: String, `as`: Identity? = null): Map<String, Any?>? {
        request("GET", path, `as`, null, emptyMap())
        val obj = when (val root = lastJsonElement()) {
            is JsonArray -> root.firstOrNull() as? JsonObject
            is JsonObject -> root
            else -> null
        } ?: return null
        return obj.mapValues { (_, v) -> v }
    }

    // ---- setup / teardown ---------------------------------------------------

    /**
     * Assert a precondition on the last request: if its status is not one of
     * [ok], throw [ProbeError] so the finding is ERROR (could-not-evaluate), not
     * a false RED. Use it to guard fixture setup.
     */
    fun precondition(vararg ok: Int) {
        val got = lastStatus
        if (got == null || got !in ok) {
            throw ProbeError("setup precondition failed: expected status in ${ok.toList()}, got $got")
        }
    }

    /**
     * Teardown that never affects the verdict: runs [block] (typically restoring
     * or deleting a disposable resource), swallowing any error and discarding
     * any assertion made inside it.
     */
    fun cleanup(block: () -> Unit) {
        val before = assertions.size
        runCatching { block() }
        while (assertions.size > before) assertions.removeLast()
    }

    // ---- expectations -------------------------------------------------------

    /** Shorthand: expect the last response to have this status. */
    fun expect(status: Int) {
        val got = lastResult?.status
        record("status == $status", status.toString(), got?.toString() ?: "<none>", got == status)
    }

    /** Block form: expect { status(201); json("[0].state") shouldBe "open" }. */
    fun expect(block: ExpectScope.() -> Unit) = ExpectScope().block()

    private fun lastJsonElement(): JsonElement? {
        if (!lastJsonParsed) {
            lastJson = lastResult?.body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            lastJsonParsed = true
        }
        return lastJson
    }

    private fun record(desc: String, expected: String, actual: String, pass: Boolean) {
        assertions += Assertion(desc, expected, actual, pass)
    }

    /** Receiver for the `expect { ... }` block. */
    inner class ExpectScope {
        fun status(code: Int) = expect(code)

        /** A JSON path in the last response body, for an equality assertion. */
        fun json(path: String): Located =
            Located("json($path)", JsonPath.render(JsonPath.get(lastJsonElement(), path)))

        /** A header of the last response, for an equality assertion. */
        fun header(name: String): Located =
            Located("header($name)", lastResult?.header(name) ?: "<missing>")

        /**
         * Assert that a JSON path resolves to a present, non-null value. The
         * value itself is never rendered — it is often a secret.
         */
        fun present(path: String) {
            val el = JsonPath.get(lastJsonElement(), path)
            val ok = el != null && el !is JsonNull
            record("json($path) is present", "<present>", if (ok) "<present>" else "<absent>", ok)
        }

        /** Assert that a JSON path resolves to nothing (missing or null). */
        fun absent(path: String) {
            val el = JsonPath.get(lastJsonElement(), path)
            val ok = el == null || el is JsonNull
            record("json($path) is absent", "<absent>", if (ok) "<absent>" else JsonPath.render(el), ok)
        }
    }

    /** A located response value with an infix equality assertion. */
    inner class Located(private val what: String, private val actual: String) {
        infix fun shouldBe(expected: Any?) {
            val exp = expected.toString()
            record("$what == $exp", exp, actual, actual == exp)
        }

        infix fun shouldNotBe(unexpected: Any?) {
            val un = unexpected.toString()
            record("$what != $un", "not $un", actual, actual != un)
        }
    }
}

/** Builds a JSON request body from `"field" to value` pairs. */
class BodyScope {
    private val fields = LinkedHashMap<String, Any?>()

    infix fun String.to(value: Any?) { fields[this] = value }

    /**
     * Seed the body from a captured object (see [ProbeScope.fetchRow]); later
     * `"field" to value` pairs override individual fields.
     */
    fun include(row: Map<String, Any?>?) { row?.let { for ((k, v) in it) fields[k] = v } }

    internal fun toJson(): String = buildJsonObject {
        for ((k, v) in fields) put(k, toJsonElement(v))
    }.toString()
}

private fun toJsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

// --- evidence redaction ------------------------------------------------------
// Request/response bodies are stored as evidence; mask anything that would turn
// a report into a credential leak. Applied at capture time (see
// ProbeScope.request) so every consumer gets it for free.

/** A signed compact token: three dot-separated base64url segments of a JSON header. */
private val COMPACT_TOKEN_RE = Regex("""eyJ[A-Za-z0-9_=-]{4,}\.[A-Za-z0-9_=-]{4,}\.[A-Za-z0-9_=-]{4,}""")

/** String values of sensitive JSON fields, e.g. "password":"hunter2" or "session_token":"…". */
private val SECRET_FIELD_RE = Regex(
    """"([A-Za-z0-9_-]*(?:password|passphrase|secret|token|authorization|credential|key))"\s*:\s*"[^"]*"""",
    RegexOption.IGNORE_CASE,
)

/** Mask tokens/secrets in a captured body; null-safe, leaves non-secret JSON intact. */
internal fun redactSecrets(s: String?): String? = s
    ?.let { SECRET_FIELD_RE.replace(it) { m -> "\"${m.groupValues[1]}\":\"<redacted>\"" } }
    ?.let { COMPACT_TOKEN_RE.replace(it, "<redacted-token>") }
