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
            // A chave e as senhas vêm dos segredos do GitHub (ASSINATURA_*). Enquanto eles não existirem,
            // usa a chave antiga do repositório, para as atualizações continuarem instalando por cima.
            fun segredo(nome: String) = System.getenv(nome)?.takeIf { it.isNotBlank() }
            storeFile = segredo("ASSINATURA_ARQUIVO")?.let { file(it) } ?: file("../app/sexta.keystore")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
