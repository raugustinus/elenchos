// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.model.Finding
import nl.codefactoring.elenchos.model.ProbeReport
import nl.codefactoring.elenchos.model.ProbeStatus
import java.time.Instant

/** Runs probes and turns what they recorded into findings. */
object ProbeRunner {

    /** Run a single probe against a context and produce a [Finding]. */
    fun run(probe: Probe, ctx: ProbeContext): Finding {
        val scope = ProbeScope(ctx)
        return try {
            probe.body(scope)
            val allPass = scope.assertions.isNotEmpty() && scope.assertions.all { it.pass }
            val status = if (allPass) ProbeStatus.GREEN else ProbeStatus.RED
            Finding(
                id = probe.id,
                title = probe.title,
                status = status,
                standards = scope.standards.toList(),
                summary = when (status) {
                    ProbeStatus.GREEN -> "Vulnerability present — the exploit still works."
                    ProbeStatus.RED -> "Not reproduced — the finding appears fixed."
                    else -> ""
                },
                assertions = scope.assertions.toList(),
                evidence = scope.evidence.toList(),
            )
        } catch (e: Throwable) {
            Finding(
                id = probe.id,
                title = probe.title,
                status = ProbeStatus.ERROR,
                standards = scope.standards.toList(),
                summary = "Could not evaluate the probe.",
                assertions = scope.assertions.toList(),
                evidence = scope.evidence.toList(),
                error = e.message ?: e.toString(),
            )
        }
    }

    /** Run a list of probes against one target, producing a [ProbeReport]. */
    fun runAll(probes: List<Probe>, ctx: ProbeContext, suite: String = ""): ProbeReport =
        ProbeReport(
            target = ctx.baseUrl,
            generatedAt = Instant.now().toString(),
            findings = probes.map { run(it, ctx) },
            suite = suite,
        )

    /** Run a [ProbeSuite] against one target. */
    fun runAll(suite: ProbeSuite, ctx: ProbeContext): ProbeReport =
        runAll(suite.probes, ctx, suite.name)
}
