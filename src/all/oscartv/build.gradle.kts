import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Oscar TV"
    qname = "OscarTv"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
}

android {
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
