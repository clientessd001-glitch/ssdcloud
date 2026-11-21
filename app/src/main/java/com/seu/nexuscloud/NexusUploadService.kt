package com.seu.nexuscloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Escopo global fora da tela para uploads continuarem mesmo com o app minimizado ou tela apagada
object NexusBackgroundScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class NexusUploadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao(this)
        adquirirWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action ?: ACTION_UPDATE
        if (acao == ACTION_STOP) {
            encerrarServicoInterno()
            return START_NOT_STICKY
        }

        val titulo = intent?.getStringExtra(EXTRA_TITLE) ?: "Nexus Cloud $NEXUS_VERSION"
        val texto = intent?.getStringExtra(EXTRA_TEXT) ?: "Enviando arquivos para a nuvem SSD..."
        val progresso = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val pausado = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false

        val notificacao = construirNotificacao(this, titulo, texto, progresso, pausado)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notificacao,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notificacao)
            }
            servicoAtivo = true
        } catch (_: Throwable) {
            // Fallback seguro caso o SO restrinja o arranque em segundo plano
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        encerrarServicoInterno()
    }

    override fun onDestroy() {
        servicoAtivo = false
        liberarWakeLock()
        super.onDestroy()
    }

    private fun encerrarServicoInterno() {
        servicoAtivo = false
        liberarWakeLock()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}
        stopSelf()
    }

    private fun adquirirWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusCloud::UploadWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                // Limite de segurança de 2 horas para nunca drenar a bateria
                wakeLock?.acquire(2L * 60L * 60L * 1000L)
            }
        } catch (_: Throwable) {}
    }

    private fun liberarWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {}
    }

    companion object {
        private const val CHANNEL_ID = "nexus_ssd_upload_channel"
        private const val NOTIF_ID = 20261
        private const val ACTION_UPDATE = "com.seu.nexuscloud.ACTION_UPLOAD_UPDATE"
        private const val ACTION_STOP = "com.seu.nexuscloud.ACTION_UPLOAD_STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_PAUSED = "extra_paused"

        @Volatile
        private var servicoAtivo = false

        @Volatile
        private var ultimaAtualizacaoMs = 0L

        @Volatile
        private var ultimoProgressoNotificado = -1

        private fun criarCanalNotificacao(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val canal = NotificationChannel(
                        CHANNEL_ID,
                        "Transferências Nexus Cloud",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Mantém os envios e backups ativos com o aplicativo minimizado"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(canal)
                }
            }
        }

        private fun construirNotificacao(
            context: Context,
            titulo: String,
            texto: String,
            progresso: Int,
            pausado: Boolean
        ): Notification {
            val intentAbrir = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intentAbrir, piFlags)

            val icone = if (pausado) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_upload
            val pctSafe = progresso.coerceIn(0, 100)

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icone)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, pctSafe, pctSafe <= 0)
                .build()
        }

        fun iniciar(context: Context, titulo: String, texto: String, progresso: Int = 5) {
            try {
                criarCanalNotificacao(context)
                ultimaAtualizacaoMs = System.currentTimeMillis()
                ultimoProgressoNotificado = progresso
                val intent = Intent(context, NexusUploadService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, titulo)
                    putExtra(EXTRA_TEXT, texto)
                    putExtra(EXTRA_PROGRESS, progresso)
                    putExtra(EXTRA_PAUSED, false)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {}
        }

        fun atualizar(
            context: Context,
            titulo: String,
            texto: String,
            progresso: Int,
            pausado: Boolean = false,
            forcar: Boolean = false
        ) {
            val agora = System.currentTimeMillis()
            val mudouPouco = !forcar &&
                !pausado &&
                (agora - ultimaAtualizacaoMs) < 450L &&
                kotlin.math.abs(progresso - ultimoProgressoNotificado) < 3
            if (mudouPouco) return

            ultimaAtualizacaoMs = agora
            ultimoProgressoNotificado = progresso

            try {
                criarCanalNotificacao(context)
                if (!servicoAtivo) {
                    iniciar(context, titulo, texto, progresso)
                    return
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.notify(NOTIF_ID, construirNotificacao(context, titulo, texto, progresso, pausado))
            } catch (_: Throwable) {}
        }

        fun encerrar(context: Context) {
            try {
                ultimoProgressoNotificado = -1
                if (servicoAtivo) {
                    val intent = Intent(context, NexusUploadService::class.java).apply {
                        action = ACTION_STOP
                    }
                    context.startService(intent)
                } else {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(NOTIF_ID)
                }
            } catch (_: Throwable) {}
        }
    }
}
