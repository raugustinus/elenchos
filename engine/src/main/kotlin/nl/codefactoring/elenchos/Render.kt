// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.model.Finding
import nl.codefactoring.elenchos.model.ProbeReport
import nl.codefactoring.elenchos.model.ProbeStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Renderers for a [ProbeReport]: machine JSON and human Markdown. */
object Report {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toJson(report: ProbeReport): String = json.encodeToString(report)
    fun toJson(finding: Finding): String = json.encodeToString(finding)

    private fun badge(s: ProbeStatus) = when (s) {
        ProbeStatus.GREEN -> "🟢 GREEN (vulnerable)"
        ProbeStatus.RED -> "🔴 RED (fixed)"
        ProbeStatus.ERROR -> "⚠️ ERROR"
    }

    fun toMarkdown(report: ProbeReport): String = buildString {
        appendLine("# Probe report")
        appendLine()
        appendLine("- Target: `${report.target}`")
        appendLine("- Generated: ${report.generatedAt}")
        appendLine("- Result: ${report.greenCount} vulnerable, ${report.redCount} fixed, ${report.errorCount} error")
        appendLine()
        for (f in report.findings) {
            append(toMarkdown(f))
            appendLine()
        }
    }

    fun toMarkdown(f: Finding): String = buildString {
        appendLine("## ${f.id} — ${f.title}")
        appendLine()
        appendLine("**${badge(f.status)}**")
        if (f.standards.isNotEmpty()) appendLine("\nStandards: ${f.standards.joinToString(", ") { it.toString() }}")
        if (f.summary.isNotEmpty()) appendLine("\n${f.summary}")
        if (f.error != null) appendLine("\n> Error: ${f.error}")
        if (f.assertions.isNotEmpty()) {
            appendLine("\n### Assertions")
            for (a in f.assertions) {
                val mark = if (a.pass) "✓" else "✗"
                appendLine("- $mark ${a.description} (got `${a.actual}`)")
            }
        }
        if (f.evidence.isNotEmpty()) {
            appendLine("\n### Evidence")
            for (e in f.evidence) {
                val who = e.identity?.let { " as $it" } ?: ""
                appendLine("- `${e.step}`$who → HTTP ${e.status}")
                if (e.requestBody != null) appendLine("  - request: `${e.requestBody}`")
                if (e.responseBody != null) appendLine("  - response: `${e.responseBody.take(500)}`")
            }
        }
    }

    // ---- report grouped by standard ----------------------------------------

    private val chunk = Regex("""\d+|\D+""")

    /** "4.10" sorts after "4.9": compare digit runs numerically, the rest as text. */
    private val naturalOrder = Comparator<String> { a, b ->
        val x = chunk.findAll(a).map { it.value }.toList()
        val y = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    /**
     * Findings grouped by [Finding.standardGroup] (framework + leading id
     * segment): groups in natural order with "unclassified" last, each group's
     * findings sorted by requirement id.
     */
    fun groups(report: ProbeReport): Map<String, List<Finding>> =
        report.findings
            .sortedWith(compareBy(naturalOrder) { it.primaryStandard?.id ?: "" })
            .groupBy { it.standardGroup }
            .toSortedMap(compareBy<String> { it == Finding.UNCLASSIFIED }.thenComparing(naturalOrder))

    /** The findings report as grouped JSON (group -> findings). */
    fun toGroupedJson(report: ProbeReport): String = json.encodeToString(groups(report))

    /** The findings report as Markdown, one section per standard group. */
    fun toGroupedMarkdown(report: ProbeReport): String = buildString {
        val title = report.suite.ifEmpty { "Findings" }
        appendLine("# $title — current status")
        appendLine()
        appendLine("- Target: `${report.target}`")
        appendLine("- Re-run: ${report.generatedAt}")
        appendLine("- Open (GREEN): ${report.greenCount} · Remediated (RED): ${report.redCount} · Error: ${report.errorCount}")
        appendLine()
        for ((group, findings) in groups(report)) {
            appendLine("## $group")
            appendLine()
            for (f in findings) {
                val req = f.primaryStandard?.id ?: "—"
                appendLine("### $req · ${f.id} — ${f.title}")
                appendLine()
                appendLine("**${badge(f.status)}**${if (f.summary.isNotEmpty()) " — ${f.summary}" else ""}")
                if (f.error != null) appendLine("\n> Error: ${f.error}")
                if (f.assertions.isNotEmpty()) {
                    for (a in f.assertions) appendLine("- ${if (a.pass) "✓" else "✗"} ${a.description} (got `${a.actual}`)")
                }
                appendLine()
            }
        }
    }

    // ---- regression (current vs baseline) ----------------------------------

    fun toJson(reg: RegressionReport): String = json.encodeToString(reg)

    fun toMarkdown(reg: RegressionReport): String = buildString {
        appendLine("# Regression check — ${reg.suite.ifEmpty { "probe suite" }}")
        appendLine()
        appendLine("- Target: `${reg.target}`")
        appendLine("- Run: ${reg.generatedAt}")
        appendLine("- Gate: ${if (reg.gatePass) "✅ PASS" else "❌ FAIL"}")
        appendLine()
        fun section(heading: String, rows: List<FindingDiff>) {
            if (rows.isEmpty()) return
            appendLine("## $heading (${rows.size})")
            for (d in rows) {
                val std = d.standard?.let { "$it · " } ?: ""
                val base = d.baseline?.let { " (was $it)" } ?: ""
                appendLine("- **${d.id}** $std${d.title} — ${d.current}$base")
            }
            appendLine()
        }
        section("🚨 Regressed (fixed → vulnerable again)", reg.regressed)
        section("🟢 Open (still vulnerable)", reg.open)
        section("⚠️ Undetermined (could not evaluate)", reg.undetermined)
        section("✅ Newly fixed", reg.fixed)
    }
}
