plugins {
    // Auto-provision the required JDK toolchain (Java 21; see build.gradle.kts) from
    // the foojay disco API when it isn't already installed locally. Saves contributors
    // from having to hand-install the exact JDK version before `./gradlew build`.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "nanoexchange"

include(":engine")
include(":network")
include(":bench")
