import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "EgyDead"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
}

dependencies {
    implementation("org.jsoup:jsoup:1.17.1")
    implementation("org.jspecify:jspecify:1.0.0")
}

android {
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
