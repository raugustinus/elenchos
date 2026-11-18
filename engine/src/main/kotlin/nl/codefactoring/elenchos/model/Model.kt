// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos.model

import kotlinx.serialization.Serializable

/**
 * The outcome of running a probe.
 *
 *  - GREEN: the exploit still works — every expectation held — so the
 *    vulnerability is PRESENT. (A green probe is bad news.)
 *  - RED:   an expectation failed — the exploit no longer reproduces — so the
 *    finding appears FIXED.
 *  - ERROR: the probe could not be evaluated (login failed, transport error,
 *    bad config). Neither confirmed nor refuted.
 */
@Serializable
enum class ProbeStatus { GREEN, RED, ERROR }

/** A reference to a requirement in some catalogue: a framework name plus an id, e.g. POLICY 4.2. */
@Serializable
data class Standard(val framework: String, val id: String) {
    override fun toString() = "$framework $id"
}

/** One captured request/response, for evidence. Secrets are redacted. */
@Serializable
data class HttpExchange(
    val step: String,
    val method: String? = null,
    val url: String? = null,
    val identity: String? = null,
    val requestBody: String? = null,
    val status: Int? = null,
    val responseBody: String? = null,
)

/** One assertion the probe made about a response. */
@Serializable
data class Assertion(
    val description: String,
    val expected: String,
    val actual: String,
    val pass: Boolean,
)

/** The structured result of a single probe — the real output, not just pass/fail. */
@Serializable
data class Finding(
    val id: String,
    val title: String,
    val status: ProbeStatus,
    val standards: List<Standard> = emptyList(),
    val summary: String = "",
    val assertions: List<Assertion> = emptyList(),
    val evidence: List<HttpExchange> = emptyList(),
    val error: String? = null,
) {
    /** True when the vulnerability is present (this is the CI failure condition). */
    val vulnerable: Boolean get() = status == ProbeStatus.GREEN

    /** The first standard this finding is tagged with, or null. */
    val primaryStandard: Standard? get() = standards.firstOrNull()

    /**
     * Report group: the primary standard's framework plus the leading segment of
     * its id (e.g. "POLICY 4" from POLICY 4.2), or "unclassified".
     */
    val standardGroup: String
        get() = primaryStandard?.let { "${it.framework} ${it.id.substringBefore('.')}" } ?: UNCLASSIFIED

    companion object {
        const val UNCLASSIFIED = "unclassified"
    }
}

/** A run of one or more probes against a single target. */
@Serializable
data class ProbeReport(
    val target: String,
    val generatedAt: String,
    val findings: List<Finding>,
    val suite: String = "",
) {
    val anyVulnerable: Boolean get() = findings.any { it.vulnerable }
    val greenCount: Int get() = findings.count { it.status == ProbeStatus.GREEN }
    val redCount: Int get() = findings.count { it.status == ProbeStatus.RED }
    val errorCount: Int get() = findings.count { it.status == ProbeStatus.ERROR }
}
