import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

repositories { mavenCentral() }

dependencies {
    // exposed on the API so consumers get the serializers transitively
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The guard test scans the whole repository (and an optional list outside
    // it), which Gradle cannot track as inputs: never reuse a previous result.
    outputs.upToDateWhen { false }
    systemProperty("elenchos.repoRoot", rootDir.absolutePath)
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Publishes this module as nl.codefactoring:elenchos to Maven Central via the Central
// Portal. Credentials and the signing key come from ~/.gradle/gradle.properties
// (or ORG_GRADLE_PROJECT_* env vars in CI), never from this repo:
//   mavenCentralUsername / mavenCentralPassword   Central Portal user token
//   signing.gnupg.keyName (+ signing.gnupg.executable)   sign via the gpg command / YubiKey
//   or signing.keyId / signing.password / signing.secretKeyRingFile, or signingInMemoryKey
//
//   ./gradlew :engine:publishToMavenCentral   upload; release it by hand in the Portal
//   ./gradlew :engine:publishToMavenLocal     local install (used by jbang/Example.kt)
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()   // manual release: nothing goes public until released in the Portal

    // Sign only when a key is configured, so publishToMavenLocal works without one.
    // Maven Central rejects unsigned uploads, so a release always needs the key.
    // signing.gnupg.keyName signs through the local `gpg` command, which is what a
    // key held on a hardware token (YubiKey) or in gpg-agent needs.
    val gpgCmdKey = providers.gradleProperty("signing.gnupg.keyName").isPresent
    val hasSigningKey = gpgCmdKey ||
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    if (hasSigningKey) signAllPublications()
    if (gpgCmdKey) {
        plugins.withId("signing") {
            extensions.configure<SigningExtension> { useGpgCmd() }
        }
    }

    coordinates(group.toString(), "elenchos", version.toString())

    pom {
        name = "elenchos"
        description = "A Kotlin DSL for writing security findings as deterministic, re-runnable " +
            "probes against any HTTP API: requests, expectations, captured evidence, reports " +
            "grouped by standard, a baseline regression diff and a bundled mock server."
        inceptionYear = "2026"
        url = "https://github.com/raugustinus/elenchos"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "codefactoring"
                name = "CodeFactoring B.V."
                url = "https://codefactoring.nl"
            }
        }
        scm {
            url = "https://github.com/raugustinus/elenchos"
            connection = "scm:git:https://github.com/raugustinus/elenchos.git"
            developerConnection = "scm:git:ssh://git@github.com/raugustinus/elenchos.git"
        }
    }
}
