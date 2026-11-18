///usr/bin/env jbang "$0" "$@" ; exit $?
// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0
//
// elenchos — a self-contained demonstration of the probe DSL. It starts the
// bundled mock server with an invented "notes" service and runs two probes
// against it, so it needs no real target:
//
//   jbang jbang/Example.kt            (after ./gradlew :engine:publishToMavenLocal)
//   ./gradlew :example:run            (same file, compiled by the build)
//
//KOTLIN 2.2.21
//REPOS mavenlocal,mavencentral
//DEPS nl.codefactoring:elenchos:0.1.0

import nl.codefactoring.elenchos.MapSecretSource
import nl.codefactoring.elenchos.ProbeContext
import nl.codefactoring.elenchos.ProbeRunner
import nl.codefactoring.elenchos.ProbeSuite
import nl.codefactoring.elenchos.Report
import nl.codefactoring.elenchos.mock.MockResponse
import nl.codefactoring.elenchos.mock.MockServer
import nl.codefactoring.elenchos.probe

// Claim 1: a member can read a note that belongs to someone else.
val READ_OTHERS_NOTE = probe("NOTE-01", "a member can read another member's note") {
    standard("POLICY", "4.2")
    val grace = bearer("grace", secret("GRACE_TOKEN"))

    get("/notes/1", `as` = grace)
    expect {
        status(200)
        json("owner") shouldBe "ada"        // GREEN: the service handed over ada's note
    }
}

// Claim 2: a member can delete a note that belongs to someone else.
val DELETE_OTHERS_NOTE = probe("NOTE-02", "a member can delete another member's note") {
    standard("POLICY", "4.3")
    val grace = bearer("grace", secret("GRACE_TOKEN"))

    delete("/notes/1", `as` = grace)
    expect { status(204) }                  // RED: the mock refuses with 403
}

val suite = ProbeSuite("Notes service — example", listOf(READ_OTHERS_NOTE, DELETE_OTHERS_NOTE))

fun main() {
    MockServer().use { mock ->
        // The deliberately flawed service: reads are not checked, deletes are.
        mock.on("GET", "/notes/1") { MockResponse(200, """{"id":1,"owner":"ada","text":"draft"}""") }
        mock.on("DELETE", "/notes/1") { MockResponse(403, """{"error":"not your note"}""") }

        val ctx = ProbeContext(mock.baseUrl, MapSecretSource(mapOf("GRACE_TOKEN" to "grace-token")))
        val report = ProbeRunner.runAll(suite, ctx)
        println(Report.toGroupedMarkdown(report))
    }
}
