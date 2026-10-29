import org.gradle.language.jvm.tasks.ProcessResources

val host = System.getProperty("os.name").lowercase()
val os = when { host.startsWith("windows") -> "windows"; host.startsWith("mac") -> "macos"; else -> "linux" }
val arch = if (System.getProperty("os.arch") in setOf("arm64", "aarch64")) "aarch64" else "x86_64"
val filename = when (os) { "windows" -> "kaudiooutput.dll"; "macos" -> "libkaudiooutput.dylib"; else -> "libkaudiooutput.so" }
val resourceRoot = layout.buildDirectory.dir("generated/jvmNative")
val destination = resourceRoot.map { it.file("native/$os-$arch/$filename") }
val compileJvm = tasks.register<Exec>("compileJvmAudioOutput") {
    val source = file("src/nativeInterop/cinterop/kaudio_${if (os == "windows") "windows.c" else if (os == "macos") "macos.m" else "linux.c"}")
    inputs.file(source); inputs.dir("src/nativeInterop/cinterop/include"); outputs.file(destination)
    doFirst {
        destination.get().asFile.parentFile.mkdirs()
        val flags = when (os) {
            "macos" -> listOf("-fobjc-arc", "-framework", "Foundation", "-framework", "CoreAudio")
            "windows" -> listOf("-lshell32")
            else -> providers.exec { commandLine("pkg-config", "--cflags", "--libs", "libpulse") }.standardOutput.asText.get().trim().split(Regex("\\s+"))
        }
        commandLine(listOf(providers.environmentVariable("KAUDIOOUTPUT_JVM_CC").orElse(if (os == "windows") "clang" else "cc").get(), "-shared", "-O2", "-fPIC",
            "-Isrc/nativeInterop/cinterop/include", source.absolutePath, "-o", destination.get().asFile.absolutePath) + flags)
    }
}
tasks.named<ProcessResources>("jvmProcessResources") { from(resourceRoot); dependsOn(compileJvm) }
providers.environmentVariable("KAUDIOOUTPUT_JVM_NATIVE_DIR").orNull?.let { supplied ->
    val copy = tasks.register<Copy>("copyJvmAudioOutputPlatforms") {
        from(supplied) { exclude("$os-$arch/**") }
        into(resourceRoot.map { it.dir("native") })
    }
    tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(copy) }
}
