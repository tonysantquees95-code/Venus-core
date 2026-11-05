plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numeroDaVersao = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.tonimarques.sextafeira"
    compileSdk = 35

    defaultConfig {
        // Mantém o identificador da Sexta-feira: a Vênus instala por cima e herda chave, memórias e histórico.
        applicationId = "com.tonimarques.sextafeira"
        resValue("string", "app_name", "Vênus")
        minSdk = 26
        targetSdk = 34
        versionCode = numeroDaVersao
        versionName = "1.$numeroDaVersao"
        // Só os processadores de celular (deixa o APK menor com o reconhecimento de voz offline).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Chave fixa: assim cada APK novo instala por cima do anterior.
    signingConfigs {
        create("fixa") {
            // A chave e as senhas vêm dos segredos do GitHub (ASSINATURA_*). Enquanto eles não existirem,
            // usa a chave antiga do repositório, para as atualizações continuarem instalando por cima.
            fun segredo(nome: String) = System.getenv(nome)?.takeIf { it.isNotBlank() }
            storeFile = segredo("ASSINATURA_ARQUIVO")?.let { file(it) } ?: file("sexta.keystore")
            storePassword = segredo("ASSINATURA_SENHA") ?: "sextafeira"
            keyAlias = segredo("ASSINATURA_APELIDO") ?: "sexta"
            keyPassword = segredo("ASSINATURA_SENHA_CHAVE") ?: segredo("ASSINATURA_SENHA") ?: "sextafeira"
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
    // Reconhecimento de voz offline (Vosk), usado só para ouvir "Vênus, ativar" sem ficar ligando e desligando o microfone.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // Recorte do produto na foto (trocar o fundo), feito no próprio celular.
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
