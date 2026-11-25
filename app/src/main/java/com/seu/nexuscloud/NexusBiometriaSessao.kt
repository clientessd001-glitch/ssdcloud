package com.seu.nexuscloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val BioGold = Color(0xFFE5B83B)
private val BioBgBlack = Color(0xFF0B0D12)
private val BioSurfaceDarker = Color(0xFF141821)
private val BioTextGrey = Color(0xFF9AA3B2)

// =========================================================================
// 1. PROTEÇÃO DE SESSÃO NO UPLOAD (IMPEDE PEDIR PIN AO VOLTAR DO SELETOR)
// =========================================================================
object NexusSessaoUpload {
    private const val PREFS = "nexus_vault_prefs"
    private const val KEY_TS = "picker_active_ts"
    private const val KEY_USER = "picker_active_user"
    private const val JANELA_MS = 90_000L // 90 segundos só para voltar do seletor

    fun registrarAberturaSeletor(context: Context, usuario: String) {
        if (usuario.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TS, System.currentTimeMillis())
            .putString(KEY_USER, usuario)
            .commit()
    }

    // Lê e já APAGA imediatamente (uso único: só vale para o retorno do seletor)
    fun consumirUsuarioPendente(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_TS, 0L)
        val user = prefs.getString(KEY_USER, null)
        prefs.edit().remove(KEY_TS).remove(KEY_USER).commit()
        val dentroDaJanela = ts > 0L && (System.currentTimeMillis() - ts) in 0L..JANELA_MS
        return if (dentroDaJanela && !user.isNullOrBlank()) user else null
    }

    fun limparAberturaSeletor(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TS)
            .remove(KEY_USER)
            .commit()
    }
}

    fun obterUsuarioPendente(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_TS, 0L)
        val user = prefs.getString(KEY_USER, null)
        val dentroDaJanela = ts > 0L && (System.currentTimeMillis() - ts) in 0L..JANELA_MS
        return if (dentroDaJanela && !user.isNullOrBlank()) user else null
    }

    fun limparAberturaSeletor(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TS)
            .remove(KEY_USER)
            .apply()
    }
}

// =========================================================================
// 2. MOTOR NATIVO DE BIOMETRIA / DIGITAL (ZERO BIBLIOTECA EXTRA NO GRADLE)
// =========================================================================
object NexusBiometria {
    private const val PREFS = "nexus_vault_prefs"
    private const val KEY_ATIVO = "biometric_unlock_enabled"

    fun estaAtiva(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ATIVO, true)
    }

    fun salvarAtiva(context: Context, ativo: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ATIVO, ativo)
            .apply()
    }

    fun disponivelNoAparelho(context: Context): Boolean {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val bm = context.getSystemService(BiometricManager::class.java)
                    bm?.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val bm = context.getSystemService(BiometricManager::class.java)
                    @Suppress("DEPRECATION")
                    bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                }
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolverActivityContext(context: Context): Context {
        var atual: Context? = context
        while (atual is ContextWrapper) {
            if (atual is Activity) return atual
            atual = atual.baseContext
        }
        return context
    }

    fun autenticar(
        context: Context,
        titulo: String,
        subtitulo: String,
        onSucesso: () -> Unit,
        onErro: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onErro("Biometria disponível a partir do Android 9.")
            return
        }
        try {
            val ctx = resolverActivityContext(context)
            val executor = ContextCompat.getMainExecutor(ctx)
            val builder = BiometricPrompt.Builder(ctx)
                .setTitle(titulo)
                .setSubtitle(subtitulo)
                .setNegativeButton("USAR PIN", executor) { _, _ -> }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
            }

            val prompt = builder.build()
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?
                    ) {
                        onSucesso()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            val msg = errString?.toString()?.ifBlank {
                                "Falha na autenticação biométrica."
                            } ?: "Falha na autenticação biométrica."
                            onErro(msg)
                        }
                    }
                }
            )
        } catch (_: SecurityException) {
            onErro("Adicione a permissão USE_BIOMETRIC no AndroidManifest.xml.")
        } catch (e: Throwable) {
            onErro("Erro na biometria: ${e.localizedMessage ?: "use o PIN"}")
        }
    }
}

// =========================================================================
// 3. COMPONENTE PRONTO PARA A TELA DE PIN (DISPARO AUTOMÁTICO + BOTÃO)
// =========================================================================
@Composable
fun BotaoBiometriaPin(
    context: Context,
    usuarioAtivo: String,
    biometriaAtiva: Boolean,
    vaultUnlocked: Boolean,
    usuarioTrancouManualmente: Boolean,
    isCheckingVault: Boolean,
    temPinLocal: Boolean = true,
    onBiometriaAprovada: () -> Unit,
    onErroBiometria: (String) -> Unit
) {
    val disponivel = remember(context) { NexusBiometria.disponivelNoAparelho(context) }
    val podeUsar = disponivel && biometriaAtiva && temPinLocal && usuarioAtivo.isNotBlank()
    var jaDisparouAuto by remember(usuarioAtivo, vaultUnlocked) { mutableStateOf(false) }

    fun dispararDigital() {
        NexusBiometria.autenticar(
            context = context,
            titulo = "Desbloquear Nexus Cloud",
            subtitulo = "Cofre: $usuarioAtivo",
            onSucesso = onBiometriaAprovada,
            onErro = onErroBiometria
        )
    }

    LaunchedEffect(podeUsar, vaultUnlocked, usuarioTrancouManualmente, isCheckingVault) {
        if (podeUsar && !vaultUnlocked && !usuarioTrancouManualmente && !isCheckingVault && !jaDisparouAuto) {
            jaDisparouAuto = true
            delay(250)
            dispararDigital()
        }
    }

    if (podeUsar) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { dispararDigital() },
            enabled = !isCheckingVault,
            border = BorderStroke(1.5.dp, BioGold),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = BioGold)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "DESBLOQUEAR COM BIOMETRIA",
                color = BioGold,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp
            )
        }
    }
}

// =========================================================================
// 4. COMPONENTE PRONTO PARA O MENU LATERAL (CHAVE LIGAR / DESLIGAR)
// =========================================================================
@Composable
fun ItemMenuBiometria(
    context: Context,
    biometriaAtiva: Boolean,
    onAlterar: (Boolean) -> Unit
) {
    val disponivel = remember(context) { NexusBiometria.disponivelNoAparelho(context) }
    if (!disponivel) return

    fun alternar(novoEstado: Boolean) {
        onAlterar(novoEstado)
        NexusBiometria.salvarAtiva(context, novoEstado)
        Toast.makeText(
            context,
            if (novoEstado) "Desbloqueio por biometria ativado." else "Biometria desativada (somente PIN).",
            Toast.LENGTH_SHORT
        ).show()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { alternar(!biometriaAtiva) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = if (biometriaAtiva) BioGold else BioTextGrey
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Desbloqueio por Biometria",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (biometriaAtiva) "Ativado (digital ou PIN)" else "Desligado (somente PIN)",
                color = if (biometriaAtiva) BioGold else BioTextGrey,
                fontSize = 10.sp
            )
        }
        Switch(
            checked = biometriaAtiva,
            onCheckedChange = { alternar(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = BioBgBlack,
                checkedTrackColor = BioGold,
                uncheckedThumbColor = BioTextGrey,
                uncheckedTrackColor = BioSurfaceDarker
            )
        )
    }
}
