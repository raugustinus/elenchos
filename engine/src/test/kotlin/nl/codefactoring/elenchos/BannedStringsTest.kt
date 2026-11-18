// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The guard: the build fails if a banned string appears anywhere in the
 * repository. The list in `banned-strings.txt` keeps the library free of
 * knowledge about specific products and standards. A second, private list is
 * read from outside the repository when present (`ELENCHOS_BANNED_FILE`, or
 * `~/.config/elenchos/banned-local.txt`), for strings that must not be
 * published even as a denylist.
 */
class BannedStringsTest {

    private val listResource = "banned-strings.txt"

    private val root: File =
        System.getProperty("elenchos.repoRoot")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }

    private fun parse(text: String): List<Regex> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Regex(it, RegexOption.IGNORE_CASE) }
            .toList()

    private fun patterns(): List<Regex> {
        val bundled = javaClass.classLoader.getResource(listResource)?.readText()
            ?: fail("$listResource is missing from the test resources")
        val local = (System.getenv("ELENCHOS_BANNED_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config/elenchos/banned-local.txt"))
        return parse(bundled) + if (local.isFile) parse(local.readText()) else emptyList()
    }

    /** Every file git would commit; without git, every file outside build and tool directories. */
    private fun candidates(): List<File> {
        val viaGit = runCatching {
            val p = ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .directory(root).redirectErrorStream(false).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) null
            else out.split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }
        }.getOrNull()
        val skipDirs = setOf(".git", ".gradle", ".idea", "build")
        return (viaGit ?: root.walkTopDown().onEnter { it.name !in skipDirs }.toList())
            .filter { it.isFile }
    }

    @Test
    fun `no banned string appears in the repository`() {
        val patterns = patterns()
        assertTrue(patterns.isNotEmpty(), "the banned list is empty")
        val files = candidates()
        assertTrue(files.size > 10, "found only ${files.size} files under $root — wrong root?")

        val hits = mutableListOf<String>()
        for (file in files) {
            val rel = file.relativeTo(root).path
            if (file.name == listResource) continue
            for (p in patterns) if (p.containsMatchIn(rel)) hits += "$rel: path matches /${p.pattern}/"
            val bytes = file.readBytes()
            if (bytes.take(8000).any { it == 0.toByte() }) continue   // binary
            bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                for (p in patterns) if (p.containsMatchIn(line)) hits += "$rel:${i + 1}: matches /${p.pattern}/"
            }
        }
        if (hits.isNotEmpty()) {
            fail("banned strings found (${hits.size}):\n" + hits.joinToString("\n") { "  $it" })
        }
    }
}
