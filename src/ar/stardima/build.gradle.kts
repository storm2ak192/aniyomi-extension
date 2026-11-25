import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Stardima"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
}

dependencies {
    implementation("org.jsoup:jsoup:1.17.1")
}

android {
    sourceSets {
        getByName("main") {
            // Placeholder launcher icon shared with the Akwam module (no Stardima artwork yet).
            res.srcDir("../akwam/res")
        }
    }

    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
