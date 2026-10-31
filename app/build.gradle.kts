plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Yanlış yere yüklenmiş eski güncelleme klasörlerini derlemeden önce temizle
file("src/main/java").walkTopDown()
    .filter { it.isDirectory && it.name.contains("klasorune") }
    .toList()
    .forEach { it.deleteRecursively() }

android {
    namespace = "com.sesliasistan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sesliasistan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Sabit imza: güncellemeler eski sürümün üzerine kurulabilsin
    signingConfigs {
        create("harun") {
            storeFile = file("harun.keystore")
            storePassword = "harun123"
            keyAlias = "harun"
            keyPassword = "harun123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("harun")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("harun")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
