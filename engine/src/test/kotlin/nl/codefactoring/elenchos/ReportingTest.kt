// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.mock.MockServer
import nl.codefactoring.elenchos.model.Finding
import nl.codefactoring.elenchos.model.ProbeReport
import nl.codefactoring.elenchos.model.ProbeStatus
import nl.codefactoring.elenchos.model.ProbeStatus.ERROR
import nl.codefactoring.elenchos.model.ProbeStatus.GREEN
import nl.codefactoring.elenchos.model.ProbeStatus.RED
import nl.codefactoring.elenchos.model.Standard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything around the DSL: JSON paths, variables, grouping, rendering, regression, the CLI helper. */
class ReportingTest {

    private fun finding(id: String, status: ProbeStatus, vararg standards: Standard) =
        Finding(id, "title of $id", status, standards.toList())

    private fun report(vararg findings: Finding) =
        ProbeReport("http://target.test", "2026-01-01T00:00:00Z", findings.toList(), "Suite")

    // ---- JsonPath -----------------------------------------------------------

    @Test
    fun `json path navigates keys and indexes`() {
        val root = Json.parseToJsonElement("""{"data":[{"id":1,"tags":["x","y"]},{"id":2}],"none":null}""")
        assertEquals(listOf<Any>("data", 0, "tags", 1), JsonPath.parse("data[0].tags[1]"))
        assertEquals("y", JsonPath.render(JsonPath.get(root, "data[0].tags[1]")))
        assertEquals("2", JsonPath.render(JsonPath.get(root, "data[1].id")))
        assertEquals("null", JsonPath.render(JsonPath.get(root, "none")))
        assertEquals("<missing>", JsonPath.render(JsonPath.get(root, "data[5].id")))
        assertEquals("<missing>", JsonPath.render(JsonPath.get(root, "data.id")))
        assertEquals("<missing>", JsonPath.render(JsonPath.get(null, "a")))
        assertEquals("""{"id":2}""", JsonPath.render(JsonPath.get(root, "data[1]")))
    }

    // ---- variables ----------------------------------------------------------

    @Test
    fun `context resolves required and optional variables`() {
        val ctx = ProbeContext.fromEnv(MapSecretSource(mapOf("BASE" to "http://t.test", "A" to "1"), fallbackToEnv = false))
        assertEquals("http://t.test", ctx.baseUrl)
        assertEquals("1", ctx.require("A"))
        assertEquals("dflt", ctx.optional("B", "dflt"))
        assertTrue(assertFailsWith<IllegalStateException> { ctx.require("B") }.message!!.contains("'B'"))
        assertFailsWith<IllegalStateException> {
            ProbeContext.fromEnv(MapSecretSource(emptyMap(), fallbackToEnv = false))
        }
    }

    @Test
    fun `map source falls back to the environment only when asked`() {
        val name = System.getenv().keys.first()
        assertEquals(System.getenv(name), MapSecretSource(emptyMap())[name])
        assertNull(MapSecretSource(emptyMap(), fallbackToEnv = false)[name])
        assertEquals("mine", MapSecretSource(mapOf(name to "mine"))[name])
    }

    // ---- grouping + rendering ----------------------------------------------

    @Test
    fun `findings group by framework and leading id segment, in natural order`() {
        val r = report(
            finding("D", GREEN),
            finding("C", RED, Standard("POLICY", "10.1")),
            finding("B", GREEN, Standard("POLICY", "4.10")),
            finding("A", GREEN, Standard("POLICY", "4.9"), Standard("OTHER", "1")),
        )
        val groups = Report.groups(r)
        assertEquals(listOf("POLICY 4", "POLICY 10", "unclassified"), groups.keys.toList())
        assertEquals(listOf("A", "B"), groups.getValue("POLICY 4").map { it.id })

        val md = Report.toGroupedMarkdown(r)
        assertTrue(md.startsWith("# Suite — current status"))
        assertTrue(md.contains("- Open (GREEN): 3 · Remediated (RED): 1 · Error: 0"))
        assertTrue(md.indexOf("## POLICY 4") < md.indexOf("## POLICY 10"))
        assertTrue(md.contains("### 4.9 · A — title of A"))
        assertTrue(md.contains("### — · D — title of D"))

        val json = Json.parseToJsonElement(Report.toGroupedJson(r)) as JsonObject
        assertEquals("C", JsonPath.render(JsonPath.get(json.getValue("POLICY 10"), "[0].id")))
    }

    @Test
    fun `flat report round-trips through JSON and renders evidence`() = MockServer().use { mock ->
        mock.on("GET", "/notes/1", 200, """{"owner":"ada"}""")
        val suite = ProbeSuite("Notes", listOf(probe("N-1", "read a note") {
            standard("POLICY", "4.2")
            get("/notes/1", `as` = bearer("grace", "t"))
            expect { json("owner") shouldBe "ada" }
        }))
        assertEquals("N-1", suite.probe("N-1")?.id)
        assertNull(suite.probe("nope"))

        val r = ProbeRunner.runAll(suite, ProbeContext(mock.baseUrl))
        assertEquals(mock.baseUrl, r.target)
        assertEquals("Notes", r.suite)
        assertTrue(r.anyVulnerable)

        val back = Json.decodeFromString(ProbeReport.serializer(), Report.toJson(r))
        assertEquals(r, back)

        val md = Report.toMarkdown(r)
        assertTrue(md.contains("## N-1 — read a note"))
        assertTrue(md.contains("Standards: POLICY 4.2"))
        assertTrue(md.contains("- ✓ json(owner) == ada (got `ada`)"))
        assertTrue(md.contains("- `GET /notes/1` as grace → HTTP 200"))
    }

    // ---- regression ---------------------------------------------------------

    @Test
    fun `regression classifies every transition and gates on open or undetermined`() {
        val baseline = report(finding("a", RED), finding("b", GREEN), finding("c", GREEN), finding("d", RED))
        val current = report(
            finding("a", GREEN, Standard("POLICY", "1.1")),  // fixed → vulnerable again
            finding("b", GREEN),                             // still open
            finding("c", RED),                               // newly fixed
            finding("d", RED),                               // still fixed
            finding("e", ERROR),                             // undetermined
            finding("f", GREEN),                             // new, open
        )
        val reg = Regression.compare(current, baseline)
        assertEquals(
            mapOf(
                "a" to RegressionKind.REGRESSED, "b" to RegressionKind.OPEN, "c" to RegressionKind.FIXED,
                "d" to RegressionKind.STILL_FIXED, "e" to RegressionKind.UNDETERMINED, "f" to RegressionKind.OPEN,
            ),
            reg.diffs.associate { it.id to it.kind },
        )
        assertEquals("POLICY 1.1", reg.diffs.first().standard)
        assertNull(reg.diffs[1].standard)
        assertFalse(reg.gatePass)

        val md = Report.toMarkdown(reg)
        assertTrue(md.contains("- Gate: ❌ FAIL"))
        assertTrue(md.contains("- **a** POLICY 1.1 · title of a — GREEN (was RED)"))

        assertTrue(Regression.compare(report(finding("c", RED)), null).gatePass)
        assertFalse(Regression.compare(report(finding("e", ERROR)), null).gatePass)
        Json.parseToJsonElement(Report.toJson(reg))   // serialises
    }

    // ---- Cli ----------------------------------------------------------------

    @Test
    fun `cli writes all reports and returns the gate as exit code`() = MockServer().use { mock ->
        mock.on("GET", "/notes/1", 200, """{"owner":"ada"}""")
        val open = probe("N-1", "open") { get("/notes/1"); expect(200) }
        val fixed = probe("N-2", "fixed") { get("/notes/1"); expect(403) }
        val ctx = ProbeContext(mock.baseUrl)
        val dir = Files.createTempDirectory("elenchos-test").toFile()
        try {
            assertEquals(1, Cli.run(ProbeSuite("S", listOf(open, fixed)), ctx, File(dir, "one"), baseline = null))
            assertEquals(
                setOf("report.json", "report.md", "grouped-report.md", "grouped-report.json", "regression.json", "regression.md"),
                File(dir, "one").list()!!.toSet(),
            )
            // the first run is the baseline: N-2 was RED, a now-GREEN N-2 is a regression
            val nowOpen = probe("N-2", "fixed") { get("/notes/1"); expect(200) }
            assertEquals(1, Cli.run(ProbeSuite("S", listOf(nowOpen)), ctx, File(dir, "two"), File(dir, "one/report.json")))
            assertTrue(File(dir, "two/regression.md").readText().contains("Regressed"))

            assertEquals(0, Cli.run(ProbeSuite("S", listOf(fixed)), ctx, File(dir, "three"), File(dir, "missing.json")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
