// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

/**
 * Where probes read secrets and other variables. Pluggable so a CI can inject
 * its own store (a vault, pipeline secrets, ...) instead of the process
 * environment. Nothing secret is ever hardcoded in a probe.
 */
fun interface SecretSource {
    /** Returns the value for [name], or null if absent. */
    operator fun get(name: String): String?
}

/** Default: read from process environment variables. */
object EnvSecretSource : SecretSource {
    override fun get(name: String): String? = System.getenv(name)
}

/** An explicit in-memory map, for tests and scripts (falls back to env if asked). */
class MapSecretSource(
    private val values: Map<String, String>,
    private val fallbackToEnv: Boolean = true,
) : SecretSource {
    override fun get(name: String): String? =
        values[name] ?: if (fallbackToEnv) System.getenv(name) else null
}

/**
 * Everything a probe needs about its target: the base URL, headers sent with
 * every request, and a variable source. Non-secret knobs (ids, account names)
 * also come through [secrets] so probe files stay free of literals.
 */
class ProbeContext(
    val baseUrl: String,
    val secrets: SecretSource = EnvSecretSource,
    val defaultHeaders: Map<String, String> = emptyMap(),
) {
    /** Required variable; throws a clear error if missing. */
    fun require(name: String): String =
        secrets[name] ?: error("missing secret/config '$name' (set it as an env var or via the SecretSource)")

    /** Optional variable with a default. */
    fun optional(name: String, default: String): String = secrets[name] ?: default

    companion object {
        /** Build from the variable source: `BASE` (target base URL) is required. */
        fun fromEnv(
            secrets: SecretSource = EnvSecretSource,
            defaultHeaders: Map<String, String> = emptyMap(),
        ): ProbeContext {
            val base = secrets["BASE"] ?: error("missing env BASE (target base URL)")
            return ProbeContext(base, secrets, defaultHeaders)
        }
    }
}
