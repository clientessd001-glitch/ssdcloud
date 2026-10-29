package com.seu.nexuscloud

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class NexusApp : Application() {
    lateinit var client: Client

    override fun onCreate() {
        super.onCreate()
        // O Cliente agora liga-se fisicamente aos servidores do Telegram
        client = Client.create(TelegramHandler(this), null, null)
        client.send(TdApi.GetAuthorizationState(), TelegramHandler(this))
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) {
            when (val state = obj.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val params = TdApi.TdlibParameters().apply {
                        databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                        useMessageDatabase = true
                        useTestDc = false
                        apiId = BuildConfig.TELEGRAM_API_ID
                        apiHash = BuildConfig.TELEGRAM_API_HASH
                        systemLanguageCode = "pt"
                        deviceModel = "Android"
                        applicationVersion = "1.0"
                    }
                    (app as NexusApp).client.send(TdApi.SetTdlibParameters(params), this)
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> Log.d("NexusCloud", "Aguardando SMS")
                is TdApi.AuthorizationStateWaitCode -> Log.d("NexusCloud", "Aguardando Código")
                is TdApi.AuthorizationStateReady -> Log.d("NexusCloud", "Autenticado!")
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NexusDB.get(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NexusUI(db.dao(), (application as NexusApp).client)
            }
        }
    }
}
