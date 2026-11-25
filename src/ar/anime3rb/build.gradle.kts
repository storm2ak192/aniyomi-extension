import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Anime3rb"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

android {
    sourceSets {
        named("test") {
            java.srcDir("test")
        }
    }
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
