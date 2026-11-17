import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Localiza na API do GitHub o commit estável da v1.8 onde TdApi.java e libtdjni.so estão pareados
fun resolverVersaoEstavelTdlib(): String {
    try {
        val apiUrl = URL("https://api.github.com/repos/capullo-tech/lib-tdlib-android/commits?per_page=15")
        val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Gradle-NexusCloud")
            connectTimeout = 15000
            readTimeout = 15000
        }
        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().readText()
            // Captura apenas SHAs de commits reais (seguidos por node_id), ignorando SHAs de tree
            val commitShas = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"\\s*,\\s*\"node_id\"")
                .findAll(json)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            println("TDLib commits reais encontrados no GitHub: $commitShas")

            // Verifica nos commits qual possui o TdApi.java compatível com o binário 89ebded9 (v1.8)
            for (sha in commitShas) {
                try {
                    val treeUrl = URL("https://api.github.com/repos/capullo-tech/lib-tdlib-android/git/trees/$sha?recursive=1")
                    val treeConn = (treeUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "Gradle-NexusCloud")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    if (treeConn.responseCode == 200) {
                        val treeJson = treeConn.inputStream.bufferedReader().readText()
                        val tdApiPath = Regex("\"path\"\\s*:\\s*\"([^\"]*TdApi\\.java)\"")
                            .find(treeJson)?.groupValues?.get(1)

                        if (tdApiPath != null) {
                            val rawUrl = URL("https://raw.githubusercontent.com/capullo-tech/lib-tdlib-android/$sha/$tdApiPath")
                            val rawConn = (rawUrl.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "Gradle-NexusCloud")
                                connectTimeout = 10000
                                readTimeout = 15000
                            }
                            if (rawConn.responseCode == 200) {
                                val conteudoTdApi = rawConn.inputStream.bufferedReader().use { r ->
                                    val buf = CharArray(120000)
                                    val lidos = r.read(buf)
                                    if (lidos > 0) String(buf, 0, lidos) else ""
                                }
                                if (conteudoTdApi.contains("89ebded9571b7bb589ec1bd05e585fffa4c580e2") ||
                                    (!conteudoTdApi.contains("class InputDocument ") && sha != commitShas.first())
                                ) {
                                    val escolhido = sha.take(10)
                                    println("TDLib commit v1.8 compatível localizado: $escolhido")
                                    return escolhido
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Aviso ao inspecionar árvore do commit $sha: ${e.message}")
                }
            }

            // Se o repositório empacota binários pré-compilados sem TdApi.java aberto, usa o commit anterior ao atual
            if (commitShas.size > 1) {
                val commitAnterior = commitShas[1].take(10)
                println("TDLib selecionando commit anterior ao main-SNAPSHOT: $commitAnterior")
                return commitAnterior
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
