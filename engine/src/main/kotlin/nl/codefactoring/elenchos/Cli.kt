// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.model.ProbeReport
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The generic headless-runner helper. A consumer that has its own suite gets the
 * whole "run → write reports → regression diff → gate" dance for free; its
 * `main` is then just:
 *
 *     fun main(): Unit = kotlin.system.exitProcess(Cli.run(MyProbes.suite))
 *
 * No probe logic lives here — only the wiring every consumer would otherwise
 * copy. The content is the [ProbeSuite] passed in.
 */
object Cli {

    /**
     * Run [suite] against [ctx], write the findings report (JSON + Markdown, flat
     * and grouped by standard) and a regression diff against an optional [baseline]
     * into [outDir], print the grouped report and the regression summary, and
     * return a process exit code: `0` when the gate passes, `1` when any finding
     * is open or regressed.
     *
     * Environment read here (all optional): `OUT_DIR` (default `elenchos-report`),
     * `BASELINE` (path to a previous `report.json`). The target (`BASE`) and any
     * probe variables come through [ctx] / its [SecretSource].
     */
    fun run(
        suite: ProbeSuite,
        ctx: ProbeContext = ProbeContext.fromEnv(),
        outDir: File = File(System.getenv("OUT_DIR") ?: "elenchos-report"),
        baseline: File? = System.getenv("BASELINE")?.let(::File),
    ): Int {
        val report = ProbeRunner.runAll(suite, ctx)

        outDir.mkdirs()
        File(outDir, "report.json").writeText(Report.toJson(report))
        File(outDir, "report.md").writeText(Report.toMarkdown(report))
        File(outDir, "grouped-report.md").writeText(Report.toGroupedMarkdown(report))
        File(outDir, "grouped-report.json").writeText(Report.toGroupedJson(report))

        val base: ProbeReport? = baseline
            ?.takeIf { it.exists() }
            ?.let { Json.decodeFromString(ProbeReport.serializer(), it.readText()) }

        val reg = Regression.compare(report, base)
        File(outDir, "regression.json").writeText(Report.toJson(reg))
        File(outDir, "regression.md").writeText(Report.toMarkdown(reg))

        println(Report.toGroupedMarkdown(report))
        println(Report.toMarkdown(reg))
        System.err.println("reports written to ${outDir.absolutePath}")

        return if (reg.gatePass) {
            System.err.println("GATE PASSED: no open or regressed findings.")
            0
        } else {
            System.err.println("GATE FAILED: open or regressed finding(s) present.")
            1
        }
    }
}
