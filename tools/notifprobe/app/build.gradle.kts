plugins {
    id("com.android.application")
}

android {
    namespace = "app.notifprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.notifprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "probe"
    productFlavors {
        create("alpha") {
            dimension = "probe"
            applicationIdSuffix = ".alpha"
            buildConfigField("int", "NOTIF_COUNT", "5")
            buildConfigField("String", "PROBE_LABEL", "\"Alpha\"")
            resValue("string", "app_name", "Probe Alpha")
        }
        create("beta") {
            dimension = "probe"
            applicationIdSuffix = ".beta"
            buildConfigField("int", "NOTIF_COUNT", "4")
            buildConfigField("String", "PROBE_LABEL", "\"Beta\"")
            resValue("string", "app_name", "Probe Beta")
        }
        create("gamma") {
            dimension = "probe"
            applicationIdSuffix = ".gamma"
            buildConfigField("int", "NOTIF_COUNT", "3")
            buildConfigField("String", "PROBE_LABEL", "\"Gamma\"")
            resValue("string", "app_name", "Probe Gamma")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}