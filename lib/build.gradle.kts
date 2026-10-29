@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm()
    android {
        namespace = "dev.brahmkshatriya.kaudiooutput"
        compileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
        minSdk = providers.gradleProperty("androidMinSdk").get().toInt()
    }
    linuxX64(); linuxArm64(); mingwX64()
    macosX64(); macosArm64(); iosArm64(); iosX64(); iosSimulatorArm64()
    js { browser() }; wasmJs { browser() }
    sourceSets {
        val nativeDesktopMain = create("nativeDesktopMain") { dependsOn(commonMain.get()) }
        val linuxMain = create("linuxMain") { dependsOn(nativeDesktopMain) }
        linuxX64Main { dependsOn(linuxMain) }; linuxArm64Main { dependsOn(linuxMain) }
        mingwX64Main { dependsOn(nativeDesktopMain) }
        val macosMain = create("macosMain") { dependsOn(nativeDesktopMain) }
        macosX64Main { dependsOn(macosMain) }; macosArm64Main { dependsOn(macosMain) }
        val iosMain = create("iosMain") { dependsOn(commonMain.get()) }
        iosArm64Main { dependsOn(iosMain) }; iosX64Main { dependsOn(iosMain) }; iosSimulatorArm64Main { dependsOn(iosMain) }
        val webMain = create("webMain") { dependsOn(commonMain.get()) }
        jsMain { dependsOn(webMain) }; wasmJsMain { dependsOn(webMain) }
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmMain.dependencies { implementation("net.java.dev.jna:jna:5.17.0") }
    }
}

val konan = File(System.getProperty("user.home"), ".konan/dependencies")
val hostOs = System.getProperty("os.name").lowercase()
val hostArm = System.getProperty("os.arch") in setOf("arm64", "aarch64")
// Kotlin/Native may download LLVM after configuration on a fresh machine.
fun konanLlvmBin() = konan.listFiles()?.filter { it.name.matches(Regex("llvm-\\d+-x86_64-linux-essentials-.*")) }
    ?.maxByOrNull { it.name.substringAfter("llvm-").substringBefore('-').toInt() }?.resolve("bin")

extensions.getByType<KotlinMultiplatformExtension>().targets.withType<KotlinNativeTarget>().configureEach {
    if (name.startsWith("ios")) return@configureEach
    val target = name
    val suffix = name.replaceFirstChar(Char::uppercaseChar)
    val stem = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
    val windows = name == "mingwX64"
    val mac = name.startsWith("macos")
    val bridgeBuildable = if (mac) hostOs.startsWith("mac") else if (windows) !hostOs.startsWith("mac") else hostOs.startsWith("linux")
    val source = file("src/nativeInterop/cinterop/kaudio_${if (windows) "windows.c" else if (mac) "macos.m" else "linux.c"}")
    val output = layout.buildDirectory.dir("native/$target")
    val archive = output.map { it.file("libkaudiooutput.a") }
    val objectFile = output.map { it.file("kaudiooutput.o") }
    val compilerOverride = providers.environmentVariable("KAUDIOOUTPUT_${stem}_CC")
    val archiverOverride = providers.environmentVariable("KAUDIOOUTPUT_${stem}_AR")
    val linuxX64Compiler = konan.resolve("x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/bin/x86_64-unknown-linux-gnu-gcc")
    fun compiler() = when {
        windows && !hostOs.startsWith("windows") -> listOf(konanLlvmBin()?.resolve("clang")?.absolutePath ?: "clang", "--target=x86_64-w64-windows-gnu",
            "--sysroot=${konan.resolve("msys2-mingw-w64-x86_64-2")}")
        mac -> listOf("clang", "-arch", if (name == "macosArm64") "arm64" else "x86_64", "-fobjc-arc")
        name == "linuxArm64" && !hostArm -> listOf(konan.resolve("aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/bin/aarch64-unknown-linux-gnu-gcc").absolutePath)
        name == "linuxX64" && hostArm -> listOf("x86_64-linux-gnu-gcc")
        name == "linuxX64" && linuxX64Compiler.isFile -> listOf(linuxX64Compiler.absolutePath)
        windows -> listOf("clang")
        else -> listOf("cc")
    }
    val headers = tasks.register<Sync>("prepareAudioHeaders$suffix") {
        onlyIf { bridgeBuildable }
        if (!windows && !mac) {
            from(providers.exec { commandLine("pkg-config", "--variable=includedir", "libpulse") }.standardOutput.asText.map { File(it.trim(), "pulse") })
            into(output.map { it.dir("include/pulse") })
        }
    }
    val compile = tasks.register<Exec>("compileAudioOutput$suffix") {
        onlyIf { bridgeBuildable }
        dependsOn(headers)
        inputs.file(source); inputs.dir("src/nativeInterop/cinterop/include")
        inputs.property("compiler", compilerOverride.orElse(providers.provider { compiler().joinToString(" ") }))
        outputs.file(objectFile)
        doFirst {
            output.get().asFile.mkdirs()
            commandLine((compilerOverride.orNull?.split(Regex("\\s+")) ?: compiler()) +
                listOf("-O2", "-fPIC", "-Isrc/nativeInterop/cinterop/include", "-I${output.get().dir("include").asFile}",
                    "-c", source.absolutePath, "-o", objectFile.get().asFile.absolutePath))
        }
    }
    val prebuilt = providers.environmentVariable("KAUDIOOUTPUT_${stem}_PREBUILT_ARCHIVE").orNull?.let(::file)
    val pack = if (prebuilt != null) tasks.register<Copy>("archiveAudioOutput$suffix") {
        from(prebuilt); into(output); rename { "libkaudiooutput.a" }
        doFirst { require(prebuilt.isFile) { "Missing audio output archive: $prebuilt" } }
    } else tasks.register<Exec>("archiveAudioOutput$suffix") {
        onlyIf { bridgeBuildable }
        dependsOn(compile); inputs.file(objectFile); outputs.file(archive)
        doFirst {
            commandLine(archiverOverride.orNull ?: if (windows) konanLlvmBin()?.resolve("llvm-ar")?.absolutePath ?: "llvm-ar" else "ar",
                "rcs", archive.get().asFile.absolutePath, objectFile.get().asFile.absolutePath)
        }
    }
    val linkDirectories = providers.environmentVariable("KAUDIOOUTPUT_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)?.filter(String::isNotBlank)
        ?: listOf(if (target == "linuxArm64") "/usr/lib/aarch64-linux-gnu" else "/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib")
    val definition = output.map { it.file("kaudiooutput.def") }
    val generateDefinition = tasks.register("generateAudioOutputDefinition$suffix") {
        val base = file("src/nativeInterop/cinterop/kaudiooutput.def")
        inputs.file(base); inputs.property("linkDirectories", linkDirectories); outputs.file(definition)
        doLast {
            output.get().asFile.mkdirs()
            val text = base.readText()
            definition.get().asFile.writeText(if (windows || mac) text else text.replace(
                "linkerOpts.linux =", "linkerOpts.linux = " + linkDirectories.joinToString(" ") { "-L$it" }))
        }
    }
    compilations.getByName("main").cinterops.create("KAudioOutput") {
        definitionFile.set(definition.get().asFile)
        includeDirs("src/nativeInterop/cinterop/include")
        extraOpts("-libraryPath", output.get().asFile.absolutePath)
    }
    tasks.matching { it.name == "cinteropKAudioOutput$suffix" }.configureEach {
        // Metadata commonization schedules cinterops for other hosts as well.
        // A skipped native archive must not leave a required, missing input.
        onlyIf { bridgeBuildable || prebuilt != null }
        dependsOn(pack, generateDefinition)
        inputs.file(archive)
    }
}

apply(from = rootProject.file("gradle/jvm-native.gradle.kts"))

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(rootProject.file("README.md"))
}

publishing {
    repositories { maven { name = "releaseShard"; url = rootProject.layout.buildDirectory.dir("maven-release-shard").get().asFile.toURI() } }
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("KAudioOutput")
            description.set("Audio output discovery and routing for Kotlin Multiplatform")
            url.set("https://github.com/brahmkshatriya/kaudiooutput")
            licenses { license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            } }
            developers { developer { id.set("brahmkshatriya"); name.set("Shivam") } }
            scm {
                url.set("https://github.com/brahmkshatriya/kaudiooutput")
                connection.set("scm:git:https://github.com/brahmkshatriya/kaudiooutput.git")
                developerConnection.set("scm:git:ssh://git@github.com/brahmkshatriya/kaudiooutput.git")
            }
        }
    }
}
