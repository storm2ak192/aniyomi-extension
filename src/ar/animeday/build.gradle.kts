import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeDay"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
}

android {
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
