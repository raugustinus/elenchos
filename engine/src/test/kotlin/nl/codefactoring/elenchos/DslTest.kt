// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.mock.MockResponse
import nl.codefactoring.elenchos.mock.MockServer
import nl.codefactoring.elenchos.model.Finding
import nl.codefactoring.elenchos.model.ProbeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DSL against the bundled mock: requests, identities, expect, capture, verdicts, evidence. */
class DslTest {

    private fun run(
        mock: MockServer,
        vars: Map<String, String> = emptyMap(),
        defaultHeaders: Map<String, String> = emptyMap(),
        body: ProbeScope.() -> Unit,
    ): Finding = ProbeRunner.run(
        probe("T-1", "test probe", body),
        ProbeContext(mock.baseUrl, MapSecretSource(vars, fallbackToEnv = false), defaultHeaders),
    )

    @Test
    fun `all expectations holding is GREEN`() = MockServer().use { mock ->
        mock.on("GET", "/notes/1", 200, """{"owner":"ada","tags":["a","b"]}""")
        val f = run(mock) {
            get("/notes/1")
            expect {
                status(200)
                json("owner") shouldBe "ada"
                json("tags[1]") shouldBe "b"
                json("owner") shouldNotBe "grace"
                present("tags")
                absent("deleted_at")
                header("Content-Type") shouldBe "application/json"
            }
        }
        assertEquals(ProbeStatus.GREEN, f.status)
        assertEquals(7, f.assertions.size)
        assertTrue(f.vulnerable)
    }

    @Test
    fun `a failed expectation is RED and records expected and actual`() = MockServer().use { mock ->
        mock.on("DELETE", "/notes/1", 403, """{"error":"forbidden"}""")
        val f = run(mock) {
            delete("/notes/1")
            expect(204)
        }
        assertEquals(ProbeStatus.RED, f.status)
        assertEquals("204", f.assertions.single().expected)
        assertEquals("403", f.assertions.single().actual)
    }

    @Test
    fun `a probe without expectations is RED, never GREEN`() = MockServer().use { mock ->
        val f = run(mock) { get("/anything") }
        assertEquals(ProbeStatus.RED, f.status)
    }

    @Test
    fun `missing variable, failed precondition and failed capture are ERROR`() = MockServer().use { mock ->
        mock.on("POST", "/members", 500, "{}")
        mock.on("GET", "/empty", 200, "{}")

        val missing = run(mock) { secret("NOPE") }
        assertEquals(ProbeStatus.ERROR, missing.status)
        assertTrue(missing.error!!.contains("NOPE"))

        val setup = run(mock) {
            post("/members") { "name" to "ada" }
            precondition(200, 201)
            expect(500)
        }
        assertEquals(ProbeStatus.ERROR, setup.status)
        assertTrue(setup.assertions.isEmpty())
        assertEquals(1, setup.evidence.size)

        val cap = run(mock) {
            get("/empty")
            captureRequired("id")
        }
        assertEquals(ProbeStatus.ERROR, cap.status)
    }

    @Test
    fun `an unreachable target is ERROR`() {
        val f = ProbeRunner.run(
            probe("T-2", "unreachable") { get("/x"); expect(200) },
            ProbeContext("http://127.0.0.1:1", MapSecretSource(emptyMap(), fallbackToEnv = false)),
        )
        assertEquals(ProbeStatus.ERROR, f.status)
        assertNotNull(f.error)
    }

    @Test
    fun `variables resolve from the source with defaults`() = MockServer().use { mock ->
        mock.on("GET", "/members/7", 200, """{"name":"ada"}""")
        val f = run(mock, vars = mapOf("MEMBER_ID" to "7")) {
            val id = secret("MEMBER_ID")
            val expected = config("EXPECTED_NAME", "ada")
            get("/members/$id")
            expect { json("name") shouldBe expected }
        }
        assertEquals(ProbeStatus.GREEN, f.status)
    }

    @Test
    fun `headers - defaults, identity and per-request, later wins`() = MockServer().use { mock ->
        mock.on("GET", "/whoami", 200, "{}")
        run(mock, defaultHeaders = mapOf("X-Tenant" to "blue", "X-Trace" to "default")) {
            val ada = bearer("ada", "ada-token", attributes = mapOf("id" to "1"))
            val key = identity("machine", headers = mapOf("X-Machine-Key" to "k"))
            assertEquals("1", ada["id"])

            get("/whoami")
            get("/whoami", `as` = ada, headers = mapOf("x-trace" to "per-request"))
            get("/whoami", `as` = key)
        }
        val (anonymous, asAda, asKey) = mock.requests
        assertEquals("blue", anonymous.header("X-Tenant"))
        assertEquals("default", anonymous.header("X-Trace"))
        assertNull(anonymous.header("Authorization"))
        assertEquals("application/json", anonymous.header("Accept"))

        assertEquals("Bearer ada-token", asAda.header("Authorization"))
        assertEquals("per-request", asAda.header("X-Trace"))

        assertEquals("k", asKey.header("X-Machine-Key"))
        assertNull(asKey.header("Authorization"))
    }

    @Test
    fun `verbs send the method and a JSON body built from pairs`() = MockServer().use { mock ->
        for (m in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) mock.on(m, "/notes", 200, "{}")
        run(mock) {
            post("/notes") {
                "text" to "hi"
                "pinned" to true
                "rank" to 3
                "parent" to null
            }
            put("/notes") { "text" to "put" }
            patch("/notes") { "text" to "patch" }
            delete("/notes")
            send("OPTIONS", "/notes")
        }
        assertEquals(listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS"), mock.requests.map { it.method })
        val post = mock.requests.first()
        assertEquals("""{"text":"hi","pinned":true,"rank":3,"parent":null}""", post.body)
        assertEquals("application/json", post.header("Content-Type"))
        assertEquals("", mock.requests[3].body)
        // nothing beyond what the probe asked for
        assertNull(post.header("Prefer"))
    }

    @Test
    fun `capture carries a value from one response into the next request`() = MockServer().use { mock ->
        mock.on("POST", "/sessions") { MockResponse(201, """{"session":{"token":"s3cr3t","member":"7"}}""", mapOf("Location" to "/sessions/9")) }
        mock.on("GET", "/members/7") { req ->
            if (req.header("Authorization") == "Bearer s3cr3t") MockResponse(200, """{"name":"ada"}""")
            else MockResponse(401, "{}")
        }
        val f = run(mock) {
            post("/sessions") { "name" to "ada"; "password" to "pw" }
            precondition(201)
            assertEquals("/sessions/9", captureHeader("location"))
            assertNull(capture("session.nope"))
            val ada = bearer("ada", captureRequired("session.token"), mapOf("id" to captureRequired("session.member")))

            get("/members/${ada["id"]}", `as` = ada)
            expect { status(200); json("name") shouldBe "ada" }
        }
        assertEquals(ProbeStatus.GREEN, f.status)
    }

    @Test
    fun `fetchRow snapshots an object and include restores it`() = MockServer().use { mock ->
        mock.on("GET", "/notes", 200, """[{"id":1,"text":"keep","pinned":true}]""")
        mock.on("POST", "/notes", 201, "{}")
        run(mock) {
            val row = fetchRow("/notes")
            post("/notes") {
                include(row)
                "text" to "changed"
            }
        }
        assertEquals("""{"id":1,"text":"changed","pinned":true}""", mock.requests.last().body)
    }

    @Test
    fun `cleanup never changes the verdict`() = MockServer().use { mock ->
        mock.on("GET", "/notes/1", 200, """{"owner":"ada"}""")
        val f = run(mock) {
            get("/notes/1")
            expect(200)
            cleanup {
                delete("/notes/1")      // 404 from the mock
                expect(204)             // would be a failing assertion
                error("teardown blew up")
            }
        }
        assertEquals(ProbeStatus.GREEN, f.status)
        assertEquals(1, f.assertions.size)
        assertEquals(2, f.evidence.size)    // the teardown request is still evidence
    }

    @Test
    fun `evidence records each exchange and never the credentials`() = MockServer().use { mock ->
        val token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZGEifQ.c2lnbmF0dXJl"
        mock.on("POST", "/sessions", 200, """{"session_token":"$token","member":{"name":"ada","api_key":"k-123"}}""")
        val f = run(mock) {
            val ada = bearer("ada", "ada-bearer-token")
            post("/sessions", `as` = ada) { "name" to "ada"; "password" to "hunter2" }
            expect(200)
        }
        val e = f.evidence.single()
        assertEquals("POST /sessions", e.step)
        assertEquals("ada", e.identity)
        assertEquals(200, e.status)
        val all = Report.toJson(f)
        for (secret in listOf("hunter2", token, "k-123", "ada-bearer-token")) {
            assertFalse(all.contains(secret), "evidence leaked '$secret'")
        }
        assertTrue(e.requestBody!!.contains(""""name":"ada""""))
        assertTrue(e.responseBody!!.contains(""""name":"ada""""))
    }

    @Test
    fun `redaction masks sensitive fields and bare tokens only`() {
        assertNull(redactSecrets(null))
        assertEquals("""{"title":"plain"}""", redactSecrets("""{"title":"plain"}"""))
        assertEquals(
            """{"Password":"<redacted>","refresh_token":"<redacted>","client_secret":"<redacted>","n":1}""",
            redactSecrets("""{"Password":"a","refresh_token":"b","client_secret":"c","n":1}"""),
        )
        assertEquals(
            "see <redacted-token> here",
            redactSecrets("see eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZGEifQ.c2lnbmF0dXJl here"),
        )
    }
}
