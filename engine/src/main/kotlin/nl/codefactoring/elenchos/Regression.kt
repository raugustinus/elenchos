// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import nl.codefactoring.elenchos.model.ProbeReport
import nl.codefactoring.elenchos.model.ProbeStatus
import kotlinx.serialization.Serializable

/** How a finding's current status relates to its baseline. */
@Serializable
enum class RegressionKind {
    REGRESSED,    // was RED (fixed), now GREEN again — the headline
    OPEN,         // GREEN now, and not a regression (still/newly vulnerable)
    FIXED,        // was GREEN, now RED — remediated since baseline
    STILL_FIXED,  // RED now and before (or no baseline) — fine
    UNDETERMINED, // ERROR — could not be evaluated
}

@Serializable
data class FindingDiff(
    val id: String,
    val title: String,
    /** The finding's primary standard rendered as "FRAMEWORK id", or null. */
    val standard: String?,
    val current: ProbeStatus,
    val baseline: ProbeStatus?,
    val kind: RegressionKind,
)

@Serializable
data class RegressionReport(
    val target: String,
    val generatedAt: String,
    val suite: String,
    val diffs: List<FindingDiff>,
) {
    val regressed get() = diffs.filter { it.kind == RegressionKind.REGRESSED }
    val open get() = diffs.filter { it.kind == RegressionKind.OPEN }
    val fixed get() = diffs.filter { it.kind == RegressionKind.FIXED }
    val undetermined get() = diffs.filter { it.kind == RegressionKind.UNDETERMINED }

    /**
     * The CI gate: fail on any OPEN or REGRESSED finding (both are currently
     * vulnerable), and on any that could not be evaluated (a probe that errors
     * can't prove the finding is fixed).
     */
    val gatePass: Boolean
        get() = diffs.none {
            it.current == ProbeStatus.GREEN || it.current == ProbeStatus.ERROR
        }
}

object Regression {
    /** Diff a current run against an optional baseline run. */
    fun compare(current: ProbeReport, baseline: ProbeReport?): RegressionReport {
        val base = baseline?.findings?.associateBy { it.id } ?: emptyMap()
        val diffs = current.findings.map { f ->
            val b = base[f.id]?.status
            val kind = when {
                f.status == ProbeStatus.ERROR -> RegressionKind.UNDETERMINED
                f.status == ProbeStatus.GREEN && b == ProbeStatus.RED -> RegressionKind.REGRESSED
                f.status == ProbeStatus.GREEN -> RegressionKind.OPEN
                f.status == ProbeStatus.RED && b == ProbeStatus.GREEN -> RegressionKind.FIXED
                else -> RegressionKind.STILL_FIXED
            }
            FindingDiff(f.id, f.title, f.primaryStandard?.toString(), f.status, b, kind)
        }
        return RegressionReport(current.target, current.generatedAt, current.suite, diffs)
    }
}
