import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Localiza automaticamente o commit estável da v1.8 no JitPack (anterior ao main-SNAPSHOT quebrado)
fun resolverVersaoEstavelTdlib(): String {
    try {
        val apiUrl = URL("https://api.github.com/repos/capullo-tech/lib-tdlib-android/commits?per_page=10")
        val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Gradle-NexusCloud")
            connectTimeout = 8000
            readTimeout = 8000
        }
        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().readText()
            val shas = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"")
                .findAll(json)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            println("TDLib commits encontrados no GitHub: $shas")

            // Testa primeiro os commits anteriores ao último (onde TdApi.java e libtdjni.so estavam alinhados na v1.8)
            val candidatos = if (shas.size > 1) shas.drop(1) + shas.first() else shas
            for (sha in candidatos) {
                val shaCurto = sha.take(10)
                val pomUrl = URL("https://jitpack.io/com/github/capullo-tech/lib-tdlib-android/$shaCurto/lib-tdlib-android-$shaCurto.pom")
                val pomConn = (pomUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (pomConn.responseCode == 200) {
                    println("TDLib versão estável selecionada no JitPack: $shaCurto")
                    return shaCurto
                }
            }
        }
    } catch (e: Exception) {
        println("Aviso ao consultar commits da TDLib: ${e.message}")
    }
    return "main-SNAPSHOT"
}

val tdlibVersion = resolverVersaoEstavelTdlib()

android {
    namespace = "com.seu.nexuscloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seu.nexuscloud"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.9"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        val apiId = System.getenv("TELEGRAM_API_ID") ?: "0"
        val apiHash = System.getenv("TELEGRAM_API_HASH") ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", apiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$apiHash\"")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/*.so")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

configurations.all {
    resolutionStrategy {
        cacheDynamicVersionsFor(0, "seconds")
        cacheChangingModulesFor(0, "seconds")
        eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("1.9.22")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("com.github.capullo-tech:lib-tdlib-android:$tdlibVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }
}
