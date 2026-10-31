plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numeroDaVersao = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.tonimarques.sextafeira"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = numeroDaVersao
        versionName = "1.$numeroDaVersao"
    }

    // Chave fixa: assim cada APK novo instala por cima do anterior.
    signingConfigs {
        create("fixa") {
            storeFile = file("sexta.keystore")
            storePassword = "sextafeira"
            keyAlias = "sexta"
            keyPassword = "sextafeira"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    // Dois apps a partir do mesmo código: a Sexta-feira (pessoal) e a Vênus (da loja).
    flavorDimensions += "marca"
    productFlavors {
        create("sexta") {
            dimension = "marca"
            applicationId = "com.tonimarques.sextafeira"
            resValue("string", "app_name", "Sexta-feira")
        }
        create("venus") {
            dimension = "marca"
            applicationId = "com.tonimarques.venus"
            resValue("string", "app_name", "Vênus")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
