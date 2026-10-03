plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "org.measly"

val fbjniRelease: String = providers.gradleProperty("fbjniRelease").get()
val fbjniVersion: String = Regex("""v(\d+\.\d+\.\d+)-r\d+""").matchEntire(fbjniRelease)?.groupValues?.get(1)
    ?: throw GradleException("fbjniRelease must look like v0.8.1-r1, got $fbjniRelease")
if (fbjniVersion != libs.versions.fbjni.get()) {
    throw GradleException("fbjniRelease $fbjniRelease does not match fbjni-java-only ${libs.versions.fbjni.get()}")
}
version = "$fbjniVersion-${providers.gradleProperty("releaseAttempt").get()}"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Upstream's fbjni-java-only 0.8.1 is Java 17 bytecode, so 17 is the floor. --release also
// rejects calls to APIs newer than 17, unlike -source/-target.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    api(libs.fbjni.java.only)
    // Upstream declares nativeloader runtime-only; the shim compiles against it
    api(libs.soloader.nativeloader)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
        }
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.measly.fbjni.natives")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "fbjni-natives", version.toString())

    pom {
        name.set("fbjni natives")
        description.set(
            "Desktop builds of Meta's fbjni (Linux x86_64 and aarch64, macOS universal2) " +
                "with a SoLoader NativeLoader delegate that loads them."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/measly-java-learning/fbjni-runtime-dist")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("corey-cole")
                name.set("Corey Cole")
                url.set("https://github.com/corey-cole")
            }
        }
        scm {
            url.set("https://github.com/measly-java-learning/fbjni-runtime-dist")
            connection.set("scm:git:git://github.com/measly-java-learning/fbjni-runtime-dist.git")
            developerConnection.set("scm:git:ssh://git@github.com/measly-java-learning/fbjni-runtime-dist.git")
        }
    }
}
