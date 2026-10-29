package com.seu.nexuscloud

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

object TdApi {
    open class Object
    open class AuthorizationState : Object()
    class AuthorizationStateWaitTdlibParameters : AuthorizationState()
    class AuthorizationStateWaitPhoneNumber : AuthorizationState()
    class AuthorizationStateWaitCode : AuthorizationState()
    class AuthorizationStateReady : AuthorizationState()
    
    class UpdateAuthorizationState(
        val authorizationState: AuthorizationState = AuthorizationStateWaitTdlibParameters()
    ) : Object()

    class TdlibParameters : Object() {
        var useTestDc = false
        var databaseDirectory = ""
        var useMessageDatabase = true
        var apiId = 0
        var apiHash = ""
        var systemLanguageCode = "pt"
        var deviceModel = "Android"
        var applicationVersion = "1.0"
    }

    class SetTdlibParameters(val parameters: TdlibParameters) : Object()
    class SetAuthenticationPhoneNumber(val phoneNumber: String) : Object()
    class CheckAuthenticationCode(val code: String) : Object()
}

class Client private constructor(private val handler: ResultHandler) {
    fun interface ResultHandler {
        fun onResult(obj: TdApi.Object)
    }

    fun send(query: TdApi.Object, callback: (TdApi.Object) -> Unit = {}) {
        when (query) {
            is TdApi.SetTdlibParameters -> handler.onResult(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
            is TdApi.SetAuthenticationPhoneNumber -> handler.onResult(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitCode()))
            is TdApi.CheckAuthenticationCode -> handler.onResult(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        }
        callback(query)
    }

    fun inicializar() {
        handler.onResult(TdApi.UpdateAuthorizationState())
    }

    companion object {
        fun create(handler: ResultHandler): Client = Client(handler)
    }
}

class NexusApp : Application() {
    lateinit var client: Client

    override fun onCreate() {
        super.onCreate()
        client = Client.create(TelegramHandler(this))
        client.inicializar()
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) {
            when (val state = obj.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val params = TdApi.TdlibParameters().apply {
                        databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                        apiId = BuildConfig.TELEGRAM_API_ID
                        apiHash = BuildConfig.TELEGRAM_API_HASH
                    }
                    (app as NexusApp).client.send(TdApi.SetTdlibParameters(params))
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> Log.d("NexusCloud", "Motor pronto: Aguardando Número")
                is TdApi.AuthorizationStateWaitCode -> Log.d("NexusCloud", "Motor pronto: Aguardando SMS")
                is TdApi.AuthorizationStateReady -> Log.d("NexusCloud", "Motor pronto: Autenticado!")
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
                // Passamos o cliente TDLib para a interface gráfica
                NexusUI(db.dao(), (application as NexusApp).client)
            }
        }
    }
}
