pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        kotlin("multiplatform") version "2.4.21"
        id("com.android.kotlin.multiplatform.library") version "9.5.0"
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "KAudioOutput"
include(":lib")
