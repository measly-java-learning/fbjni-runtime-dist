import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

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

// Release archive platform suffix -> jar resource directory under fbjni-natives/
val nativeArchives = mapOf(
    "linux-x86_64" to "linux-x86_64",
    "linux-aarch64" to "linux-aarch64",
    "macos-universal2" to "macos",
)
val nativeArchivePrefix = "fbjni-${fbjniRelease.removePrefix("v")}"
// Persistent cache outside build/, so it survives `./gradlew clean`
val nativeArchiveDir = File(gradle.gradleUserHomeDir, "caches/fbjni-natives/$fbjniRelease")

val fetchNatives = tasks.register("fetchNatives") {
    description = "Downloads the pinned fbjni-conan release archives and verifies them against natives/SHA256SUMS."
    // Local copies: task actions must not capture script-scope references (configuration cache)
    val release = fbjniRelease
    val archiveDir = nativeArchiveDir
    val archives = nativeArchives.keys.map { "$nativeArchivePrefix-$it.tar.gz" }
    val sums = rootProject.file("natives/SHA256SUMS")
    inputs.file(sums)
    inputs.property("release", release)
    outputs.files(archives.map { File(archiveDir, it) })
    doLast {
        fun sha256(file: File): String = HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        val expected = sums.readLines().filter { it.isNotBlank() }.associate { line ->
            val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
            name to hash
        }
        archiveDir.mkdirs()
        for (name in archives) {
            val want = expected[name] ?: throw GradleException("natives/SHA256SUMS has no entry for $name")
            val file = File(archiveDir, name)
            if (file.exists() && sha256(file) == want) {
                continue
            }
            val url = "https://github.com/measly-java-learning/fbjni-conan/releases/download/$release/$name"
            logger.lifecycle("Downloading $url")
            val part = File(archiveDir, "$name.part")
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != want) {
                part.delete()
                throw GradleException("$name has SHA-256 $actual, but natives/SHA256SUMS says $want")
            }
            Files.move(
                part.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}

val prepareNatives = tasks.register<Sync>("prepareNatives") {
    description = "Extracts libfbjni from each release archive and writes fbjni-natives.properties."
    dependsOn(fetchNatives)
    for ((suffix, dir) in nativeArchives) {
        from(tarTree(File(nativeArchiveDir, "$nativeArchivePrefix-$suffix.tar.gz"))) {
            include("*/lib/libfbjni.*")
            eachFile { path = "fbjni-natives/$dir/$name" }
        }
    }
    into(layout.buildDirectory.dir("generated/natives"))
    includeEmptyDirs = false
    val outDir = layout.buildDirectory.dir("generated/natives/fbjni-natives").get().asFile
    val resourceDirs = nativeArchives.values.sorted()
    val manifestVersion = version.toString()
    val release = fbjniRelease
    inputs.property("version", manifestVersion)
    doLast {
        val lines = mutableListOf("version=$manifestVersion", "nativeRelease=$release")
        for (dir in resourceDirs) {
            val lib = File(outDir, dir).listFiles()?.singleOrNull()
                ?: throw GradleException("expected exactly one libfbjni in $outDir/$dir")
            val hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(lib.readBytes()))
            lines += "sha256.$dir=$hash"
        }
        File(outDir, "fbjni-natives.properties").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

sourceSets {
    main {
        resources.srcDir(prepareNatives)
    }
}

// Smoke-test JVM: a toolchain of smokeJavaVersion (default 17), or the JDK at smokeJavaHome. CI uses
// smokeJavaHome for an x86_64 JDK on an arm64 Mac, since a toolchain request cannot name an architecture.
val smokeJavaVersion = providers.gradleProperty("smokeJavaVersion").getOrElse("17").toInt()
val smokeJavaHome: String? = providers.gradleProperty("smokeJavaHome").orNull
val smokeExpectArch: String? = providers.gradleProperty("smokeExpectArch").orNull

// The host's resource directory, for the system-mode smoke test's java.library.path
val hostResourceDir = providers.systemProperty("os.name").get().lowercase().let { os ->
    when {
        os.startsWith("mac") -> "macos"
        providers.systemProperty("os.arch").get() in setOf("amd64", "x86_64") -> "linux-x86_64"
        else -> "linux-aarch64"
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
        }
        // Runs against the built jar, not the class files, on JDK 17 unless smokeJavaVersion or smokeJavaHome says otherwise
        register<JvmTestSuite>("smokeTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(files(tasks.jar))
                implementation(libs.fbjni.java.only)
                implementation(libs.soloader.nativeloader)
            }
            targets.all {
                testTask.configure {
                    if (smokeJavaHome != null) {
                        executable = File(smokeJavaHome, "bin/java").path
                    } else {
                        javaLauncher = javaToolchains.launcherFor {
                            languageVersion = JavaLanguageVersion.of(smokeJavaVersion)
                        }
                    }
                    // Checked by BundledExtractionSmokeTest, so a run on the wrong JVM fails
                    systemProperty("fbjni.smoke.javaVersion", smokeJavaVersion)
                    if (smokeExpectArch != null) {
                        systemProperty("fbjni.smoke.osArch", smokeExpectArch)
                    }
                    // NativeLoader is global: one JVM per mode
                    forkEvery = 1
                    systemProperty(
                        "java.library.path",
                        layout.buildDirectory.dir("generated/natives/fbjni-natives/$hostResourceDir").get().asFile.path,
                    )
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("smokeTest"))
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
