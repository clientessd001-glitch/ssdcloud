package com.seu.nexuscloud

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

object TdApi {
    open class Object
    open class AuthorizationState : Object()
    class AuthorizationStateWaitTdlibParameters : AuthorizationState()
    class UpdateAuthorizationState(
        val authorizationState: AuthorizationState = AuthorizationStateWaitTdlibParameters()
    ) : Object()

    class TdlibParameters : Object() {
        var useTestDc: Boolean = false
        var databaseDirectory: String = ""
        var useMessageDatabase: Boolean = true
        var apiId: Int = 0
        var apiHash: String = ""
        var systemLanguageCode: String = "pt"
        var deviceModel: String = "Android"
        var applicationVersion: String = "1.0"
    }

    class SetTdlibParameters(val parameters: TdlibParameters) : Object()
}

class Client private constructor(private val handler: ResultHandler) {
    fun interface ResultHandler {
        fun onResult(obj: TdApi.Object)
    }

    fun send(query: TdApi.Object, callback: (TdApi.Object) -> Unit = {}) {
        callback(query)
    }

    fun inicializar() {
        handler.onResult(TdApi.UpdateAuthorizationState())
    }

    companion object {
        fun create(
            handler: ResultHandler,
            updateExceptionHandler: Any?,
            defaultExceptionHandler: Any?
        ): Client {
            return Client(handler)
        }
    }
}

class NexusApp : Application() {
    lateinit var client: Client

    override fun onCreate() {
        super.onCreate()
        client = Client.create(TelegramHandler(this), null, null)
        client.inicializar()
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState && obj.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
            val params = TdApi.TdlibParameters().apply {
                useTestDc = false
                databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                useMessageDatabase = true
                apiId = BuildConfig.TELEGRAM_API_ID
                apiHash = BuildConfig.TELEGRAM_API_HASH
                systemLanguageCode = "pt"
                deviceModel = "Android"
                applicationVersion = "1.0"
            }
            (app as NexusApp).client.send(TdApi.SetTdlibParameters(params)) {}
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NexusDB.get(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NexusUI(db.dao())
            }
        }
    }
}
