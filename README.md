# elenchos

<!-- SPDX-License-Identifier: Apache-2.0 -->
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A small Kotlin DSL and runtime for writing security claims as **deterministic,
executable probes** against any HTTP API. It is an abstraction layer only: it
knows about HTTP, JSON and reports, and nothing about any particular backend,
identity product or standards catalogue. You bring the probes.

A **probe** is a parameterised reproduction of one claim against a running
target: it sends requests as some identity and asserts on the responses. A probe
that stays **GREEN** means the claim still reproduces (the weakness is present);
it goes **RED** when it no longer does (fixed), and **ERROR** when it could not
be evaluated. The output is a structured **Finding** with the assertions and
the captured HTTP evidence, not just pass/fail.

> **The delivered report and the re-runnable regression suite are the same
> artifact.** Re-running the suite re-issues the report with today's status.

## Use it

```kotlin
dependencies {
    implementation("nl.codefactoring:elenchos:0.1.0")
}
```

```kotlin
import nl.codefactoring.elenchos.*
import kotlin.system.exitProcess

val READ_OTHERS_NOTE = probe("NOTE-01", "a member can read another member's note") {
    standard("POLICY", "4.2")                    // optional tag, any catalogue you like
    val grace = bearer("grace", secret("GRACE_TOKEN"))   // variables are never hardcoded
    val noteId = config("NOTE_ID", "1")

    get("/notes/$noteId", `as` = grace)
    expect {
        status(200)
        json("owner") shouldBe "ada"             // GREEN: the service handed over ada's note
    }
}

val suite = ProbeSuite("Notes service", listOf(READ_OTHERS_NOTE))

fun main(): Unit = exitProcess(Cli.run(suite))
```

`Cli.run` runs the suite against `BASE`, writes `report.{json,md}`,
`grouped-report.{json,md}` and `regression.{json,md}` into `OUT_DIR`, prints the
grouped report, and returns a non-zero exit code if any finding is open,
regressed or undetermined. `ProbeRunner.run` / `runAll` give you the same
findings as objects if you would rather drive them from a test framework.

### Try it without a target

[`jbang/Example.kt`](jbang/Example.kt) starts the bundled mock server with an
invented notes service and runs two probes against it:

```bash
./gradlew :example:run
```

### DSL cheat-sheet

| In a probe body | Does |
|---|---|
| `secret("X")` / `config("X", default)` | required / optional variable from the `SecretSource` |
| `bearer(name, token)` / `identity(name, headers)` | who to send a request as; credentials never reach the evidence |
| `get/post/put/patch/delete(path, as = id, headers = …) { "field" to value }` | send a request with a JSON body built from pairs |
| `send(method, path, as, headers) { … }` | any other method |
| `capture("a.b[0]")` / `captureRequired(…)` / `captureHeader(name)` | carry a response value into a later step |
| `fetchRow(path, as)` + `include(row)` | snapshot an object, and put it back later |
| `precondition(200, 201)` | guard setup: ERROR instead of a false RED when it fails |
| `expect { status(201); json("x") shouldBe v; header("h") shouldBe v; present(p); absent(p) }` | assertions; **all hold ⇒ GREEN** |
| `cleanup { … }` | teardown that never affects the verdict |
| `standard(framework, id)` | tag the finding; reports group by it |

The engine adds only `Accept: application/json`, plus `Content-Type` when there
is a body. Anything a particular target needs on every request goes in
`ProbeContext(baseUrl, secrets, defaultHeaders = …)`; anything it needs to log
in is a few lines of your own on top of `post`, `capture` and `bearer`.

## Variables (never hardcoded)

Probes read everything through a pluggable `SecretSource` (default: process
environment variables; `MapSecretSource` for tests and scripts). The library
itself reads only:

| Var | Meaning |
|---|---|
| `BASE` | target base URL (`ProbeContext.fromEnv()`) |
| `BASELINE` | path to a previous `report.json` for the regression diff (optional) |
| `OUT_DIR` | where `Cli.run` writes reports (default `elenchos-report`) |

Everything else is whatever **your** probes ask for via `secret(...)` /
`config(...)`.

## Regression mode

Set `BASELINE` to a previous `report.json`. A finding that was RED (fixed) and
is GREEN again is flagged **REGRESSED**. The gate (`Cli.run`'s exit code) is
non-zero on any **open**, **regressed** or **undetermined** finding.

## Mock server

`nl.codefactoring.elenchos.mock.MockServer` is a tiny in-process HTTP server on
the JDK's built-in one: script routes with `on(method, path) { request -> MockResponse(…) }`,
point a `ProbeContext` at `baseUrl`, and inspect `requests` afterwards. The
library's own tests use it, and it is handy for developing a probe before you
have access to the target.

## Build

The code targets **JVM 21**. Build on **JDK 21+** (`.sdkmanrc` pins a 21 JDK;
`sdk env` picks it up).

```bash
sdk env
./gradlew build                           # unit tests + the banned-strings guard
./gradlew :engine:publishToMavenLocal     # install nl.codefactoring:elenchos:0.1.0 locally
```

The build fails if any string listed in
[`banned-strings.txt`](engine/src/test/resources/banned-strings.txt) appears
anywhere in the repository: the library must stay free of product- and
standard-specific knowledge. Probes for a specific stack or engagement belong in
their own project that depends on this one.

## Dependencies (small on purpose)

Kotlin and kotlinx-serialization-json. HTTP uses the JDK's built-in
`java.net.http` client; the mock uses the JDK's built-in server. No test
framework is imposed on consumers.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
Copyright 2026 CodeFactoring B.V.
