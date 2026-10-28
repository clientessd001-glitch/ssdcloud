package com.seu.nexuscloud

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi

class NexusApp : Application() {
    lateinit var client: Client
    override fun onCreate() {
        super.onCreate()
        client = Client.create(TelegramHandler(this), null, null)
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState && obj.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
            val params = TdApi.TdlibParameters().apply {
                databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                useMessageDatabase = true
                apiId = BuildConfig.TELEGRAM_API_ID
                apiHash = BuildConfig.TELEGRAM_API_HASH
                systemLanguageCode = "pt"; deviceModel = "Android"; applicationVersion = "1.0"
            }
            (app as NexusApp).client.send(TdApi.SetTdlibParameters(params)) {}
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NexusDB.get(this)
        setContent { NexusUI(db.dao()) }
    }
}
