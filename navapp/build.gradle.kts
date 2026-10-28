import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse(localProperties.getProperty("MAPS_API_KEY") ?: "")
    .get()

android {
    namespace = "net.prototux.aare.nav"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.prototux.aare.navigation"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Pass -PMAPS_API_KEY=... or put MAPS_API_KEY=... in local.properties.
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.libraries.navigation:navigation:8.0.0")
    implementation("com.google.android.libraries.places:places:4.1.0") {
        // The Navigation SDK already embeds the Maps SDK classes.
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
