plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numeroDaVersao = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

/**
 * Vênus Robô: app separado que responde o WhatsApp e o Instagram da loja pelas notificações,
 * perguntando cada resposta à Vênus da loja (site). Separado da Vênus para que, se o Android
 * bloquear apps que leem notificações, a Vênus principal continue instalando normalmente.
 */
android {
    namespace = "com.tonimarques.venusrobo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tonimarques.venusrobo"
        resValue("string", "app_name", "Vênus Robô")
        minSdk = 26
        targetSdk = 34
        versionCode = numeroDaVersao
        versionName = "1.$numeroDaVersao"
    }

    signingConfigs {
        create("fixa") {
            storeFile = file("../app/sexta.keystore")
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
