import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Anime3rb"
    versionCode = 8
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
            (this as org.gradle.api.plugins.ExtensionAware).extensions.findByName("kotlin")?.let {
                (it as org.gradle.api.file.SourceDirectorySet).srcDir("test")
            }
        }
    }
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
