import java.net.HttpURLConnection
import java.net.URL

// Aumenta o timeout HTTP do Gradle para 3 minutos para evitar "Read timed out" no JitPack
System.setProperty("org.gradle.internal.http.connectionTimeout", "180000")
System.setProperty("org.gradle.internal.http.socketTimeout", "180000")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

fun aquecerJitPack(shaCurto: String): Boolean {
    val urls = listOf(
        "https://jitpack.io/com/github/capullo-tech/lib-tdlib-android/$shaCurto/lib-tdlib-android-$shaCurto.pom",
        "https://jitpack.io/com/github/capullo-tech/lib-tdlib-android/$shaCurto/lib-tdlib-android-$shaCurto.aar"
    )
    for (urlStr in urls) {
        var ok = false
        for (tentativa in 1..2) {
            try {
                println("Aguardando JitPack preparar $shaCurto (tentativa $tentativa)...")
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Gradle-NexusCloud")
                    connectTimeout = 30000
                    readTimeout = 180000
                }
                val code = conn.responseCode
                if (code == 200) {
                    conn.inputStream.use { it.read() }
                    ok = true
                    break
                } else {
                    println("JitPack respondeu HTTP $code para $urlStr")
                }
            } catch (e: Exception) {
                println("Aviso JitPack ($shaCurto - tentativa $tentativa): ${e.message}")
            }
        }
        if (!ok) return false
    }
    return true
}

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
            val commitShas = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"\\s*,\\s*\"node_id\"")
                .findAll(json)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            println("TDLib commits reais encontrados no GitHub: $commitShas")

            for (sha in commitShas) {
                val shaCurto = sha.take(10)
                try {
                    val treeUrl = URL("https://api.github.com/repos/capullo-tech/lib-tdlib-android/git/trees/$sha?recursive=1")
                    val treeConn = (treeUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "Gradle-NexusCloud")
                        connectTimeout = 15000
                        readTimeout = 15000
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
                                connectTimeout = 15000
                                readTimeout = 60000
                            }
                            if (rawConn.responseCode == 200) {
                                var hashLinha = ""
                                var temInputDocument = false
                                rawConn.inputStream.bufferedReader().useLines { lines ->
                                    for (line in lines) {
                                        if (line.contains("COMMIT_HASH =")) {
                                            hashLinha = line.trim()
                                        }
                                        if (line.contains("class InputDocument ")) {
                                            temInputDocument = true
                                        }
                                    }
                                }
                                println("Commit $shaCurto -> $hashLinha | InputDocument=$temInputDocument")

                                val ehCompativel = hashLinha.contains("89ebded9571b7bb589ec1bd05e585fffa4c580e2") ||
                                    (!temInputDocument && sha != commitShas.first())

                                if (ehCompativel) {
                                    if (aquecerJitPack(shaCurto)) {
                                        println("TDLib commit v1.8 pronto no JitPack: $shaCurto")
                                        return shaCurto
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Aviso ao inspecionar commit $shaCurto: ${e.message}")
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
