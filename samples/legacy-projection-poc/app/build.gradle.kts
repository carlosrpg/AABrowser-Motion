import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.kododake.aabrowser.projectionpoc"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kododake.aabrowser.projectionpoc"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use(::load)
                }
            }

            fun signingValue(key: String): String? =
                project.findProperty(key) as? String
                    ?: localProperties.getProperty(key)
                    ?: System.getenv(key)

            storeFile = rootProject.file(
                signingValue("RELEASE_STORE_FILE") ?: "release.keystore"
            )
            signingValue("RELEASE_STORE_PASSWORD")?.let { storePassword = it }
            signingValue("RELEASE_KEY_ALIAS")?.let { keyAlias = it }
            signingValue("RELEASE_KEY_PASSWORD")?.let { keyPassword = it }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(files("libs/aauto.aar"))
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.car.app:app:1.7.0")
}
