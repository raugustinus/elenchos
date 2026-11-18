plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "nl.codefactoring"
    version = "0.1.0"
}
