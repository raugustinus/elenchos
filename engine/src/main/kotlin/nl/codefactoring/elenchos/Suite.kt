// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

/**
 * A named group of probes — the executable form of a findings report. A probe
 * may tag itself with a requirement (`standard(framework, id)` in its body), so
 * a run can be grouped by framework and requirement.
 *
 * The delivered report and this re-runnable suite are the same artifact:
 * re-running re-issues the report with today's status.
 */
data class ProbeSuite(
    val name: String,
    val probes: List<Probe>,
) {
    fun probe(id: String): Probe? = probes.firstOrNull { it.id == id }
}
