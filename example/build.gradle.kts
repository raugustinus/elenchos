import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Not published. Compiles jbang/Example.kt against the engine so the shipped
// example cannot drift from the API:  ./gradlew :example:run
plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":engine"))
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    sourceSets.main { kotlin.srcDir("../jbang") }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass = "ExampleKt"
}
