package com.seu.nexuscloud

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

const val NEXUS_VERSION = "v1.3"

val BgBlack = Color(0xFF05070A)
val SurfaceDark = Color(0xFF161A25)
val SurfaceDarker = Color(0xFF10131B)
val Gold = Color(0xFFFFD700)
val DangerRed = Color(0xFFE53935)
val TextGrey = Color(0xFFA0AABF)

object NexusTransfer {
    val progress = MutableStateFlow<Int?>(null)
    val label = MutableStateFlow("")
}

fun limparPrefixoTimestamp(nome: String): String {
    return nome.trim()
        .replace(Regex("^thumb_\\d+_"), "")
        .replace(Regex("^\\d{10,16}_"), "")
        .trim()
}

class UIViewModel(private val dao: NexusDao) : ViewModel() {
    val arquivos = dao.buscarTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastas = dao.buscarPastas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val armazenamento = dao.calcularUsoTotal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    suspend fun obterArquivosReais(): List<ArquivoNuvem> = withContext(Dispatchers.IO) {
        dao.buscarTodos().first()
    }

    suspend fun obterPastasReais(): List<PastaNuvem> = withContext(Dispatchers.IO) {
        dao.buscarPastas().first()
    }

    fun criarPasta(nome: String) = viewModelScope.launch {
        val limpo = nome.trim()
        if (limpo.isNotBlank()) {
            val atuais = obterPastasReais()
            val jaExiste = atuais.any { it.nomePasta.trim().equals(limpo, ignoreCase = true) }
            if (!jaExiste) {
                dao.criarPasta(PastaNuvem(nomePasta = limpo))
            }
        }
    }

    fun salvarArquivo(arq: ArquivoNuvem) = viewModelScope.launch {
        val atuais = obterArquivosReais()
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)
        val existente = atuais.firstOrNull {
            limparPrefixoTimestamp(it.nomeArquivo).equals(nomeLimpo, ignoreCase = true) &&
                (it.tamanhoBytes == arq.tamanhoBytes || it.tamanhoBytes == 0L || arq.tamanhoBytes == 0L)
        }
        if (existente != null) {
            val thumbAntigaOk = miniaturaExisteLocalmente(existente.caminhoMiniatura)
            val thumbNovaOk = miniaturaExisteLocalmente(arq.caminhoMiniatura)
            if (!thumbAntigaOk && thumbNovaOk) {
                dao.apagarArquivo(existente.id)
                dao.salvarArquivo(
                    existente.copy(
                        id = 0,
                        nomeArquivo = nomeLimpo,
                        caminhoMiniatura = arq.caminhoMiniatura,
                        fileId = if (arq.fileId != 0) arq.fileId else existente.fileId
                    )
                )
            }
        } else {
            dao.salvarArquivo(arq.copy(nomeArquivo = nomeLimpo))
        }
    }

    fun apagar(id: Int) = viewModelScope.launch { dao.apagarArquivo(id) }

    fun atualizarCaminhoMiniatura(arq: ArquivoNuvem, novoCaminho: String) = viewModelScope.launch {
        if (novoCaminho.isNotBlank() && arq.caminhoMiniatura != novoCaminho) {
            dao.apagarArquivo(arq.id)
            dao.salvarArquivo(arq.copy(id = 0, nomeArquivo = limparPrefixoTimestamp(arq.nomeArquivo), caminhoMiniatura = novoCaminho))
        }
    }

    fun renomearArquivo(arq: ArquivoNuvem, novoNome: String) = viewModelScope.launch {
        if (novoNome.isNotBlank()) {
            dao.apagarArquivo(arq.id)
            dao.salvarArquivo(arq.copy(id = 0, nomeArquivo = novoNome.trim()))
        }
    }
}

// === GESTÃO DE COFRES MULTIUTILIZADOR, ANTI-DUPLICAÇÃO E EXCLUSÃO ===
fun normalizarUsuario(nome: String): String = nome.trim().lowercase(Locale.ROOT)

fun obterUltimoUsuarioCofre(context: Context): String {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_active_user", "") ?: ""
}

fun salvarCredenciaisCofreLocal(context: Context, nomeOriginal: String, pin: String) {
    val chave = normalizarUsuario(nomeOriginal)
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putString("pin_$chave", pin.trim())
        .putString("display_$chave", nomeOriginal.trim())
        .putString("last_active_user", nomeOriginal.trim())
        .apply()
}

fun obterPinCofreLocal(context: Context, nome: String): String? {
    val chave = normalizarUsuario(nome)
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("pin_$chave", null)
}

fun carregarDonosFicheiros(context: Context): Map<Long, String> {
    val prefs = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<Long, String>()
    for ((k, v) in prefs.all) {
        val ts = k.toLongOrNull()
        if (ts != null && v is String && v.isNotBlank()) mapa[ts] = v
    }
    return mapa
}

fun salvarDonoFicheiro(context: Context, dataUpload: Long, usuario: String) {
    val prefs = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
    if (usuario.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), normalizarUsuario(usuario)).apply()
}

fun carregarPastasDoUsuario(context: Context, usuario: String): Set<String> {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
    return prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet()
}

fun associarPastaAoUsuario(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
    val atuais = (prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet()).toMutableSet()
    val jaTem = atuais.any { it.equals(nomePasta.trim(), ignoreCase = true) }
    if (!jaTem) {
        atuais.add(nomePasta.trim())
        prefs.edit().putStringSet("folders_$chave", atuais).apply()
    }
}

fun registarMensagemSincronizada(context: Context, usuario: String, msgId: Long) {
    if (msgId == 0L) return
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_synced_msgs_$chave", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(msgId.toString(), true).apply()
}

fun mensagemJaSincronizada(context: Context, usuario: String, msgId: Long): Boolean {
    if (msgId == 0L) return false
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_synced_msgs_$chave", Context.MODE_PRIVATE)
    return prefs.getBoolean(msgId.toString(), false)
}

fun marcarFicheiroComoSincronizado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long, uriStr: String = "") {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_auto_synced_files_$chave", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putBoolean("name_$nomeLimpo", true)
    editor.putBoolean("key_${nomeLimpo}|$tamanho", true)
    if (uriStr.isNotBlank()) {
        editor.putBoolean("uri_$uriStr", true)
    }
    editor.apply()
}

fun ficheiroJaSincronizadoAntes(context: Context, usuario: String, nomeArquivo: String, tamanho: Long, uriStr: String = ""): Boolean {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_auto_synced_files_$chave", Context.MODE_PRIVATE)
    if (uriStr.isNotBlank() && prefs.getBoolean("uri_$uriStr", false)) return true
    if (prefs.getBoolean("key_${nomeLimpo}|$tamanho", false)) return true
    if (prefs.getBoolean("name_$nomeLimpo", false)) return true
    return false
}

fun marcarFicheiroComoDeletado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long) {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("del_name_$nomeLimpo", true)
        .putBoolean("del_key_${nomeLimpo}|$tamanho", true)
        .apply()
}

fun desmarcarFicheiroDeletado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long) {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    prefs.edit()
        .remove("del_name_$nomeLimpo")
        .remove("del_key_${nomeLimpo}|$tamanho")
        .apply()
}

fun ficheiroFoiDeletadoPeloUsuario(context: Context, usuario: String, nomeArquivo: String, tamanho: Long): Boolean {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    return prefs.getBoolean("del_key_${nomeLimpo}|$tamanho", false) ||
        prefs.getBoolean("del_name_$nomeLimpo", false)
}

fun eliminarFicheiroCompleto(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arq: ArquivoNuvem,
    apagarNaNuvemTelegram: Boolean,
    vm: UIViewModel,
    onComplete: (String) -> Unit
) {
    val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)

    // 1. Marca como deletado e sincronizado para não voltar a ser importado nem pedido no auto-sync
    marcarFicheiroComoDeletado(context, usuarioAtivo, nomeLimpo, arq.tamanhoBytes)
    marcarFicheiroComoSincronizado(context, usuarioAtivo, nomeLimpo, arq.tamanhoBytes)

    // 2. Apaga a miniatura local e o frame de vídeo/PDF da memória do telemóvel
    try {
        val thumb = arq.caminhoMiniatura ?: ""
        if (thumb.startsWith("file://") || thumb.startsWith("/")) {
            val limpo = if (thumb.startsWith("file://")) Uri.parse(thumb).path ?: thumb.removePrefix("file://") else thumb
            if (limpo.isNotBlank()) {
                val f = File(limpo)
                if (f.exists()) f.delete()
                val fFrame = File("${limpo}_frame.jpg")
                if (fFrame.exists()) fFrame.delete()
            }
        }
    } catch (_: Exception) {}

    // 3. Remove vínculos de pasta, dono e registo no Room Database
    salvarVinculoPasta(context, arq.dataUpload, "")
    salvarDonoFicheiro(context, arq.dataUpload, "")
    vm.apagar(arq.id)

    if (!apagarNaNuvemTelegram) {
        onComplete("'$nomeLimpo' removido do aplicativo.")
        return
    }

    // 4. Apaga o cache da TDLib e elimina a mensagem nas Mensagens Salvas do Telegram
    if (arq.fileId != 0) {
        client.send(TdApi.DeleteFile().apply { fileId = arq.fileId }) {}
    }

    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarHistoricoPaginado(client, chatObj.id, 0L, 5, mutableListOf()) { todasMensagens ->
                        val idsParaApagar = mutableListOf<Long>()
                        for (msg in todasMensagens) {
                            val docContent = msg.content as? TdApi.MessageDocument ?: continue
                            val legenda = docContent.caption?.text ?: ""
                            val pertenceAoUsuario = legenda.contains(tagPerfil, ignoreCase = true) ||
                                legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                                legenda.contains("☁️ Nexus Cloud")

                            if (pertenceAoUsuario) {
                                val rId = docContent.document?.document?.id ?: 0
                                val nomeBruto = docContent.document?.fileName ?: ""
                                val nomeMsg = extrairNomeLimpoDaMensagem(nomeBruto, legenda)
                                val tamMsg = docContent.document?.document?.size ?: 0L

                                val mesmoId = arq.fileId != 0 && rId == arq.fileId
                                val mesmoNome = nomeMsg.equals(nomeLimpo, ignoreCase = true) &&
                                    (tamMsg == arq.tamanhoBytes || tamMsg == 0L || arq.tamanhoBytes == 0L)

                                if (mesmoId || mesmoNome) {
                                    idsParaApagar.add(msg.id)
                                }
                            }
                        }

                        if (idsParaApagar.isNotEmpty()) {
                            val delReq = TdApi.DeleteMessages().apply {
                                this.chatId = chatObj.id
                                this.messageIds = idsParaApagar.toLongArray()
                                this.revoke = true
                            }
                            client.send(delReq) {
                                onComplete("'$nomeLimpo' deletado do app e da nuvem!")
                            }
                        } else {
                            onComplete("'$nomeLimpo' deletado!")
                        }
                    }
                } else {
                    onComplete("'$nomeLimpo' deletado localmente.")
                }
            }
        } else {
            onComplete("'$nomeLimpo' deletado localmente.")
        }
    }
}

fun migrarDadosV11SeNecessario(context: Context, usuario: String, arquivos: List<ArquivoNuvem>, pastas: List<PastaNuvem>) {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean("migrated_v11_data", false)) {
        val donosAtuais = carregarDonosFicheiros(context)
        arquivos.forEach { arq ->
            if (!donosAtuais.containsKey(arq.dataUpload)) {
                salvarDonoFicheiro(context, arq.dataUpload, usuario)
            }
        }
        pastas.forEach { p ->
            associarPastaAoUsuario(context, usuario, p.nomePasta)
        }
        prefs.edit().putBoolean("migrated_v11_data", true).apply()
    }
}

fun miniaturaExisteLocalmente(caminhoMiniatura: String?): Boolean {
    val c = caminhoMiniatura ?: return false
    if (c.isBlank()) return false
    if (c.startsWith("content://")) return true
    val limpo = if (c.startsWith("file://")) Uri.parse(c).path ?: c.removePrefix("file://") else c
    return limpo.isNotBlank() && File(limpo).exists()
}

fun limparDuplicadosExistentes(
    context: Context,
    todosArquivos: List<ArquivoNuvem>,
    vm: UIViewModel,
    onCleaned: () -> Unit
) {
    if (todosArquivos.isEmpty()) return
    val donos = carregarDonosFicheiros(context)
    val pastas = carregarVinculosPastas(context)

    val grupos = todosArquivos.groupBy { arq ->
        val dono = donos[arq.dataUpload] ?: ""
        val nomeBase = limparPrefixoTimestamp(arq.nomeArquivo).lowercase(Locale.ROOT)
        "${dono}|${nomeBase}|${arq.tamanhoBytes}"
    }

    var alterouAlgum = false
    for ((_, listaGrupo) in grupos) {
        if (listaGrupo.size > 1) {
            val principal = listaGrupo.sortedWith(
                compareByDescending<ArquivoNuvem> { miniaturaExisteLocalmente(it.caminhoMiniatura) }
                    .thenByDescending { !pastas[it.dataUpload].isNullOrBlank() }
                    .thenBy { it.nomeArquivo.length }
                    .thenBy { it.id }
            ).first()

            val pastaEncontrada = listaGrupo.firstNotNullOfOrNull { pastas[it.dataUpload] }
            if (!pastaEncontrada.isNullOrBlank() && pastas[principal.dataUpload].isNullOrBlank()) {
                salvarVinculoPasta(context, principal.dataUpload, pastaEncontrada)
            }

            val donoEncontrado = listaGrupo.firstNotNullOfOrNull { donos[it.dataUpload] }
            if (!donoEncontrado.isNullOrBlank()) {
                if (donos[principal.dataUpload].isNullOrBlank()) {
                    salvarDonoFicheiro(context, principal.dataUpload, donoEncontrado)
                }
                marcarFicheiroComoSincronizado(context, donoEncontrado, principal.nomeArquivo, principal.tamanhoBytes)
            }

            listaGrupo.forEach { item ->
                if (item.id != principal.id) {
                    vm.apagar(item.id)
                    alterouAlgum = true
                }
            }
            val nomeLimpoPrincipal = limparPrefixoTimestamp(principal.nomeArquivo)
            if (principal.nomeArquivo != nomeLimpoPrincipal) {
                vm.renomearArquivo(principal, nomeLimpoPrincipal)
                alterouAlgum = true
            }
        } else {
            val unico = listaGrupo.first()
            val donoUnico = donos[unico.dataUpload] ?: ""
            if (donoUnico.isNotBlank()) {
                marcarFicheiroComoSincronizado(context, donoUnico, unico.nomeArquivo, unico.tamanhoBytes)
            }
            val nomeLimpo = limparPrefixoTimestamp(unico.nomeArquivo)
            if (unico.nomeArquivo != nomeLimpo) {
                vm.renomearArquivo(unico, nomeLimpo)
                alterouAlgum = true
            }
        }
    }

    if (alterouAlgum) {
        onCleaned()
    }
}

fun buscarHistoricoPaginado(
    client: Client,
    chatId: Long,
    fromMsgId: Long,
    lotesRestantes: Int,
    acumulado: MutableList<TdApi.Message>,
    onComplete: (List<TdApi.Message>) -> Unit
) {
    if (lotesRestantes <= 0) {
        onComplete(acumulado)
        return
    }
    val req = TdApi.GetChatHistory().apply {
        this.chatId = chatId
        this.fromMessageId = fromMsgId
        this.offset = 0
        this.limit = 100
        this.onlyLocal = false
    }
    client.send(req) { obj ->
        if (obj is TdApi.Messages && obj.messages.isNotEmpty()) {
            acumulado.addAll(obj.messages)
            val ultimoId = obj.messages.last().id
            if (ultimoId != 0L && ultimoId != fromMsgId) {
                buscarHistoricoPaginado(client, chatId, ultimoId, lotesRestantes - 1, acumulado, onComplete)
            } else {
                onComplete(acumulado)
            }
        } else {
            onComplete(acumulado)
        }
    }
}

fun verificarOuRegistarCofreNuvem(
    client: Client,
    nomeUsuario: String,
    pin: String,
    modoCriarNovo: Boolean,
    onResult: (Boolean, String, String) -> Unit
) {
    val chaveDesejada = normalizarUsuario(nomeUsuario)
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarHistoricoPaginado(client, chatObj.id, 0L, 5, mutableListOf()) { todasMensagens ->
                        var pinEncontrado: String? = null
                        var nomeOriginalEncontrado = nomeUsuario.trim()

                        for (msg in todasMensagens) {
                            val texto = when (val c = msg.content) {
                                is TdApi.MessageText -> c.text?.text ?: ""
                                is TdApi.MessageDocument -> c.caption?.text ?: ""
                                else -> ""
                            }
                            if (texto.contains("🔒 [REGISTO_COFRE]")) {
                                val partes = texto.split("|").map { it.trim() }
                                val userPart = partes.firstOrNull { it.startsWith("USER:") }?.removePrefix("USER:")?.trim()
                                val nomePart = partes.firstOrNull { it.startsWith("NOME:") }?.removePrefix("NOME:")?.trim()
                                val pinPart = partes.firstOrNull { it.startsWith("PIN:") }?.removePrefix("PIN:")?.trim()
                                if (userPart != null && userPart == chaveDesejada && pinPart != null) {
                                    pinEncontrado = pinPart
                                    if (!nomePart.isNullOrBlank()) nomeOriginalEncontrado = nomePart
                                    break
                                }
                            }
                        }

                        if (modoCriarNovo) {
                            if (pinEncontrado != null) {
                                onResult(false, "", "Este nome já está em uso! Escolha outro (ex: ${nomeUsuario.trim()}85).")
                            } else {
                                val regTexto = "🔒 [REGISTO_COFRE] | USER:$chaveDesejada | NOME:${nomeUsuario.trim()} | PIN:${pin.trim()}"
                                val msgContent = TdApi.InputMessageText().apply {
                                    text = TdApi.FormattedText(regTexto, emptyArray())
                                }
                                val sendReq = TdApi.SendMessage().apply {
                                    chatId = chatObj.id
                                    inputMessageContent = msgContent
                                }
                                client.send(sendReq) {
                                    onResult(true, nomeUsuario.trim(), "Cofre criado com sucesso!")
                                }
                            }
                        } else {
                            if (pinEncontrado == null) {
                                onResult(false, "", "Cofre '${nomeUsuario.trim()}' não encontrado. Use a aba 'CRIAR COFRE'.")
                            } else if (pinEncontrado == pin.trim()) {
                                onResult(true, nomeOriginalEncontrado, "Cofre desbloqueado!")
                            } else {
                                onResult(false, "", "PIN incorreto para o utilizador '$nomeOriginalEncontrado'.")
                            }
                        }
                    }
                } else {
                    onResult(false, "", "Erro ao aceder ao cofre na nuvem.")
                }
            }
        } else {
            onResult(false, "", "Sessão TDLib indisponível.")
        }
    }
}

fun prepararMiniaturaDeFicheiroBaixado(context: Context, arquivoOrigem: File, nomeArquivo: String): String {
    return try {
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val nomeSeguro = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(dir, "thumb_${arquivoOrigem.length()}_$nomeSeguro")
        if (!f.exists()) {
            FileInputStream(arquivoOrigem).use { inp ->
                FileOutputStream(f).use { out -> inp.copyTo(out) }
            }
        }
        val mime = descobrirMimeType(nomeSeguro)
        if (mime.startsWith("video/")) {
            val frameFile = File("${f.absolutePath}_frame.jpg")
            if (!frameFile.exists()) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(f.absolutePath)
                    val bitmap = retriever.getFrameAtTime(1000000) ?: retriever.getFrameAtTime(0)
                    retriever.release()
                    if (bitmap != null) {
                        FileOutputStream(frameFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
                } catch (_: Exception) {}
            }
        } else if (mime == "application/pdf") {
            gerarCapaPdf(f)
        }
        Uri.fromFile(f).toString()
    } catch (_: Exception) {
        Uri.fromFile(arquivoOrigem).toString()
    }
}

fun extrairNomeLimpoDaMensagem(nomeDocumento: String, legenda: String): String {
    val daLegenda = if (legenda.contains("• ")) {
        legenda.substringAfterLast("• ").trim()
    } else ""
    val candidato = if (daLegenda.isNotBlank() && daLegenda.contains(".")) daLegenda else nomeDocumento
    return limparPrefixoTimestamp(candidato).ifBlank { "Arquivo_Nuvem" }
}

fun sincronizarCofreDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arquivosAtuaisReais: List<ArquivoNuvem>,
    vm: UIViewModel,
    onComplete: (Int) -> Unit
) {
    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarHistoricoPaginado(client, chatObj.id, 0L, 5, mutableListOf()) { todasMensagens ->
                        var importados = 0
                        val donosAtuais = carregarDonosFicheiros(context)

                        val chavesExistentes = arquivosAtuaisReais
                            .filter { donosAtuais[it.dataUpload] == chaveAtiva || donosAtuais.isEmpty() }
                            .map { "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}" }
                            .toMutableSet()

                        val nomesExistentes = arquivosAtuaisReais
                            .filter { donosAtuais[it.dataUpload] == chaveAtiva || donosAtuais.isEmpty() }
                            .map { limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT) }
                            .toMutableSet()

                        val idsExistentes = arquivosAtuaisReais.map { it.fileId }.filter { it != 0 }.toMutableSet()

                        todasMensagens.forEachIndexed { index, msg ->
                            val docContent = msg.content as? TdApi.MessageDocument
                            val legenda = docContent?.caption?.text ?: ""
                            if (docContent != null && (legenda.contains(tagPerfil, ignoreCase = true) || legenda.contains(tagPerfilExibicao, ignoreCase = true))) {
                                val rId = docContent.document?.document?.id ?: 0
                                val nomeBruto = docContent.document?.fileName?.ifBlank { "Arquivo_$rId" } ?: "Arquivo_$rId"
                                val nomeDoc = extrairNomeLimpoDaMensagem(nomeBruto, legenda)
                                val nomeLower = nomeDoc.lowercase(Locale.ROOT)
                                val tam = docContent.document?.document?.size ?: 0L
                                val chaveUnica = "$nomeLower|$tam"

                                // Se o ficheiro foi deletado pelo utilizador, ignora-o na sincronização
                                if (ficheiroFoiDeletadoPeloUsuario(context, usuarioAtivo, nomeDoc, tam)) {
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)
                                    return@forEachIndexed
                                }

                                val pastaExtraida = if (legenda.contains("📁 [")) {
                                    legenda.substringAfter("📁 [").substringBefore("]").trim()
                                } else ""

                                if (pastaExtraida.isNotBlank()) {
                                    vm.criarPasta(pastaExtraida)
                                    associarPastaAoUsuario(context, usuarioAtivo, pastaExtraida)
                                }

                                marcarFicheiroComoSincronizado(context, usuarioAtivo, nomeDoc, tam)

                                val jaExiste = mensagemJaSincronizada(context, usuarioAtivo, msg.id) ||
                                    chavesExistentes.contains(chaveUnica) ||
                                    nomesExistentes.contains(nomeLower) ||
                                    (rId != 0 && idsExistentes.contains(rId))

                                if (!jaExiste) {
                                    chavesExistentes.add(chaveUnica)
                                    nomesExistentes.add(nomeLower)
                                    if (rId != 0) idsExistentes.add(rId)
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)

                                    val ts = (msg.date.toLong() * 1000L) + (msg.id % 997L).let { if (it < 0) -it else it } + index
                                    salvarDonoFicheiro(context, ts, usuarioAtivo)
                                    if (pastaExtraida.isNotBlank()) {
                                        salvarVinculoPasta(context, ts, pastaExtraida)
                                    }

                                    val caminhoLocalExistente = docContent.document?.document?.local?.path ?: ""
                                    if (caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists()) {
                                        val uriMiniatura = prepararMiniaturaDeFicheiroBaixado(context, File(caminhoLocalExistente), nomeDoc)
                                        vm.salvarArquivo(
                                            ArquivoNuvem(
                                                fileId = rId,
                                                nomeArquivo = nomeDoc,
                                                tamanhoBytes = tam,
                                                dataUpload = ts,
                                                caminhoMiniatura = uriMiniatura
                                            )
                                        )
                                    } else {
                                        vm.salvarArquivo(
                                            ArquivoNuvem(
                                                fileId = rId,
                                                nomeArquivo = nomeDoc,
                                                tamanhoBytes = tam,
                                                dataUpload = ts,
                                                caminhoMiniatura = ""
                                            )
                                        )
                                        if (rId != 0) {
                                            val dlReq = TdApi.DownloadFile().apply {
                                                fileId = rId
                                                priority = 1
                                                synchronous = false
                                            }
                                            client.send(dlReq) {}
                                        }
                                    }
                                    importados++
                                } else {
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)
                                }
                            }
                        }
                        onComplete(importados)
                    }
                } else onComplete(0)
            }
        } else onComplete(0)
    }
}

fun garantirFicheiroLocal(
    context: Context,
    client: Client,
    arq: ArquivoNuvem,
    vm: UIViewModel,
    onReady: (ArquivoNuvem) -> Unit
) {
    if (miniaturaExisteLocalmente(arq.caminhoMiniatura)) {
        onReady(arq)
        return
    }
    if (arq.fileId == 0) {
        Toast.makeText(context, "Ficheiro indisponível localmente.", Toast.LENGTH_SHORT).show()
        return
    }
    NexusTransfer.progress.value = 35
    NexusTransfer.label.value = "A recuperar '${arq.nomeArquivo}' da nuvem..."
    val req = TdApi.DownloadFile().apply {
        fileId = arq.fileId
        priority = 32
        synchronous = true
    }
    client.send(req) { res ->
        NexusTransfer.progress.value = null
        if (res is TdApi.File) {
            val pathRemoto = res.local?.path ?: ""
            if (pathRemoto.isNotBlank() && File(pathRemoto).exists()) {
                val novoThumb = prepararMiniaturaDeFicheiroBaixado(context, File(pathRemoto), arq.nomeArquivo)
                val atualizado = arq.copy(caminhoMiniatura = novoThumb)
                vm.atualizarCaminhoMiniatura(arq, novoThumb)
                onReady(atualizado)
            } else {
                onReady(arq)
            }
        } else {
            onReady(arq)
        }
    }
}

// === DETETOR DE NOVOS FICHEIROS NAS PASTAS SELECIONADAS (v1.3) ===
data class PastaSync(val uri: String, val nome: String, var ativa: Boolean)

data class NovoArquivoSync(
    val uri: Uri,
    val nome: String,
    val tamanho: Long,
    val nomePastaOrigem: String
)

fun carregarPastasSync(context: Context, usuario: String): List<PastaSync> {
    val sufixo = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE)
    val lista = mutableListOf<PastaSync>()
    prefs.all.forEach { (uriStr, ativo) ->
        if (ativo is Boolean) {
            val decodedPath = Uri.decode(uriStr)
            val nome = decodedPath.substringAfterLast(":", "Pasta").substringAfterLast("/")
            lista.add(PastaSync(uriStr, nome.ifBlank { "Pasta Selecionada" }, ativo))
        }
    }
    return lista.distinctBy { it.uri }
}

fun salvarPastaSync(context: Context, usuario: String, uri: String, ativa: Boolean) {
    val sufixo = normalizarUsuario(usuario)
    context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE).edit().putBoolean(uri, ativa).apply()
}

fun removerPastaSync(context: Context, usuario: String, uri: String) {
    val sufixo = normalizarUsuario(usuario)
    context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE).edit().remove(uri).apply()
}

fun procurarNovosFicheirosNasPastas(
    context: Context,
    usuarioAtivo: String,
    arquivosNoCofre: List<ArquivoNuvem>
): List<NovoArquivoSync> {
    if (usuarioAtivo.isBlank()) return emptyList()
    val pastasAtivas = carregarPastasSync(context, usuarioAtivo).filter { it.ativa }
    if (pastasAtivas.isEmpty()) return emptyList()

    val chavesNoCofre = arquivosNoCofre.map {
        "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}"
    }.toSet()
    val nomesNoCofre = arquivosNoCofre.map {
        limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)
    }.toSet()

    val encontrados = mutableListOf<NovoArquivoSync>()
    val chavesJaAdicionadas = mutableSetOf<String>()

    for (pasta in pastasAtivas) {
        try {
            val treeUri = Uri.parse(pasta.uri)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idxId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = if (idxId != -1 && !cursor.isNull(idxId)) cursor.getString(idxId) else continue
                    val nomeBruto = if (idxName != -1 && !cursor.isNull(idxName)) cursor.getString(idxName) else continue
                    val tamanho = if (idxSize != -1 && !cursor.isNull(idxSize)) cursor.getLong(idxSize) else 0L
                    val mime = if (idxMime != -1 && !cursor.isNull(idxMime)) cursor.getString(idxMime) else ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val nome = limparPrefixoTimestamp(nomeBruto)
                    if (nome.startsWith(".") || tamanho <= 0L) continue

                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val uriStr = fileUri.toString()
                    val nomeLower = nome.lowercase(Locale.ROOT)
                    val chave = "$nomeLower|$tamanho"

                    val jaSincronizado = chavesNoCofre.contains(chave) ||
                        nomesNoCofre.contains(nomeLower) ||
                        chavesJaAdicionadas.contains(chave) ||
                        ficheiroJaSincronizadoAntes(context, usuarioAtivo, nome, tamanho, uriStr) ||
                        ficheiroFoiDeletadoPeloUsuario(context, usuarioAtivo, nome, tamanho)

                    if (!jaSincronizado) {
                        chavesJaAdicionadas.add(chave)
                        encontrados.add(
                            NovoArquivoSync(
                                uri = fileUri,
                                nome = nome,
                                tamanho = tamanho,
                                nomePastaOrigem = pasta.nome
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
    return encontrados
}
// =================================================================

fun formatarDataNuvem(timestamp: Long): String {
    if (timestamp <= 0L) return "Data desconhecida"
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun formatarDataCurta(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
}

fun formatarTempoVideo(ms: Int): String {
    val totalSegundos = (ms / 1000).coerceAtLeast(0)
    val minutos = totalSegundos / 60
    val segundos = totalSegundos % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutos, segundos)
}

fun carregarVinculosPastas(context: Context): Map<Long, String> {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<Long, String>()
    for ((k, v) in prefs.all) {
        val chave = k.toLongOrNull()
        if (chave != null && v is String && v.isNotBlank()) mapa[chave] = v
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, dataUpload: Long, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    if (nomePasta.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), nomePasta).apply()
}

fun obterDetalhesFicheiro(context: Context, uri: Uri): Pair<String, Long> {
    var nome = "Arquivo_${System.currentTimeMillis()}"
    var tamanho = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iT = c.getColumnIndex(OpenableColumns.SIZE)
            if (iN != -1 && !c.isNull(iN)) nome = c.getString(iN)
            if (iT != -1 && !c.isNull(iT)) tamanho = c.getLong(iT)
        }
    }
    return Pair(limparPrefixoTimestamp(nome), tamanho)
}

fun descobrirMimeType(nome: String): String {
    return when (nome.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4", "mov", "mkv", "avi" -> "video/mp4"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "csv", "log", "json", "xml", "md" -> "text/plain"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

fun gerarCapaPdf(arquivoPdf: File): File? {
    return try {
        val frameFile = File("${arquivoPdf.absolutePath}_frame.jpg")
        if (frameFile.exists()) return frameFile
        ParcelFileDescriptor.open(arquivoPdf, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { page ->
                        val largura = (page.width * 2).coerceIn(300, 1200)
                        val altura = (page.height * 2).coerceIn(400, 1600)
                        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(frameFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        return frameFile
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

fun renderizarPaginasPdf(caminhoMiniatura: String?): List<Bitmap> {
    val lista = mutableListOf<Bitmap>()
    try {
        val caminho = caminhoMiniatura ?: return emptyList()
        val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
        val arq = File(limpo)
        if (!arq.exists()) return emptyList()

        ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount.coerceAtMost(20)
                for (i in 0 until total) {
                    renderer.openPage(i).use { page ->
                        val largura = (page.width * 2).coerceIn(400, 1400)
                        val altura = (page.height * 2).coerceIn(500, 1800)
                        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        lista.add(bmp)
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return lista
}

fun lerTextoDocumento(caminhoMiniatura: String?): String? {
    return try {
        val caminho = caminhoMiniatura ?: return null
        val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
        val arq = File(limpo)
        if (!arq.exists()) return null
        arq.bufferedReader().use { it.readText().take(40000) }
    } catch (_: Exception) {
        null
    }
}

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nomeArquivo: String): String {
    return try {
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val nomeSeguro = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(dir, "thumb_${System.currentTimeMillis()}_$nomeSeguro")
        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(f).use { out -> inp.copyTo(out) }
        }
        val mime = descobrirMimeType(nomeSeguro)
        if (mime.startsWith("video/")) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(f.absolutePath)
                val bitmap = retriever.getFrameAtTime(1000000) ?: retriever.getFrameAtTime(0)
                retriever.release()
                if (bitmap != null) {
                    val frameFile = File("${f.absolutePath}_frame.jpg")
                    FileOutputStream(frameFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            } catch (_: Exception) {}
        } else if (mime == "application/pdf") {
            gerarCapaPdf(f)
        }
        Uri.fromFile(f).toString()
    } catch (_: Exception) {
        uri.toString()
    }
}

fun obterUriCapaMidia(caminhoMiniatura: String?): Uri {
    val caminho = caminhoMiniatura ?: ""
    if (caminho.startsWith("file://")) {
        val limpo = Uri.parse(caminho).path ?: caminho.removePrefix("file://")
        val frameFile = File("${limpo}_frame.jpg")
        if (frameFile.exists()) return Uri.fromFile(frameFile)
        val arqOriginal = File(limpo)
        if (arqOriginal.exists() && limpo.endsWith(".pdf", ignoreCase = true)) {
            val gerado = gerarCapaPdf(arqOriginal)
            if (gerado != null) return Uri.fromFile(gerado)
        }
    }
    return Uri.parse(caminho)
}

fun temCapaPdfLocal(caminhoMiniatura: String?): Boolean {
    val caminho = caminhoMiniatura ?: return false
    if (caminho.startsWith("file://")) {
        val limpo = Uri.parse(caminho).path ?: caminho.removePrefix("file://")
        val frameFile = File("${limpo}_frame.jpg")
        if (frameFile.exists()) return true
        val arqOriginal = File(limpo)
        if (arqOriginal.exists()) return gerarCapaPdf(arqOriginal) != null
    }
    return false
}

fun copiarUriParaCacheTDLib(context: Context, uri: Uri, nomeArquivo: String): File? {
    return try {
        val subDir = File(context.filesDir, "tdlib_upload_cache/${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
        val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(subDir, nomeLimpo)
        context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
        f
    } catch (_: Exception) { null }
}

fun prepararUriPublica(context: Context, caminhoMiniatura: String?, nomeArquivo: String): Pair<Uri, String>? {
    val caminho = caminhoMiniatura ?: ""
    if (caminho.isBlank()) return null

    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    val mime = descobrirMimeType(nomeArquivo)
    if (caminho.startsWith("content://")) {
        val u = Uri.parse(caminho)
        return Pair(u, context.contentResolver.getType(u) ?: mime)
    }

    val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
    val arq = File(limpo)
    if (!arq.exists()) return null

    val col = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }
    val vals = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Nexus_${System.currentTimeMillis()}_$nomeArquivo")
        put(MediaStore.MediaColumns.MIME_TYPE, if (mime == "*/*") "application/octet-stream" else mime)
    }
    val uriMedia = try {
        context.contentResolver.insert(col, vals)?.also { d ->
            context.contentResolver.openOutputStream(d)?.use { o -> arq.inputStream().use { it.copyTo(o) } }
        }
    } catch (_: Exception) { null }

    return Pair(uriMedia ?: Uri.fromFile(arq), mime)
}

fun abrirFicheiroExterno(context: Context, caminhoMiniatura: String?, nomeArquivo: String) {
    try {
        val preparado = prepararUriPublica(context, caminhoMiniatura, nomeArquivo)
        if (preparado == null) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem' primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = preparado
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (mime == "*/*") "application/octet-stream" else mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(viewIntent, "Abrir $nomeArquivo com..."))
    } catch (e: Exception) {
        Toast.makeText(context, "Nenhuma app encontrada para abrir este formato.", Toast.LENGTH_SHORT).show()
    }
}

fun compartilharFicheiroNativo(context: Context, caminhoMiniatura: String?, nomeArquivo: String) {
    try {
        val preparado = prepararUriPublica(context, caminhoMiniatura, nomeArquivo)
        if (preparado == null) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem'.", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = preparado
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar $nomeArquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao partilhar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun enviarParaMensagensSalvas(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    caminhoLocal: String,
    nomeOriginal: String,
    pastaDestino: String,
    onResult: (Boolean, Int, String) -> Unit
) {
    val nomeLimpo = limparPrefixoTimestamp(nomeOriginal)
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val userTag = "👤 [$usuarioAtivo] • "
                    val pastaTag = if (pastaDestino.isNotBlank()) "📁 [$pastaDestino] • " else ""
                    val leg = TdApi.FormattedText("☁️ Nexus Cloud • $userTag$pastaTag$nomeLimpo", emptyArray())
                    val doc = TdApi.InputMessageDocument(TdApi.InputFileLocal(caminhoLocal), null, false, leg)
                    val req = TdApi.SendMessage().apply { chatId = chatObj.id; inputMessageContent = doc }
                    client.send(req) { msgObj ->
                        when (msgObj) {
                            is TdApi.Message -> {
                                registarMensagemSincronizada(context, usuarioAtivo, msgObj.id)
                                val rId = (msgObj.content as? TdApi.MessageDocument)?.document?.document?.id ?: 0
                                val txt = if (pastaDestino.isNotBlank()) "Guardado na pasta '$pastaDestino'!" else "Guardado no cofre de $usuarioAtivo!"
                                onResult(true, rId, txt)
                            }
                            is TdApi.Error -> onResult(false, 0, "Erro TDLib (${msgObj.code}): ${msgObj.message}")
                            else -> onResult(true, 0, "Upload iniciado!")
                        }
                    }
                } else if (chatObj is TdApi.Error) onResult(false, 0, "Erro cofre: ${chatObj.message}")
            }
        } else if (meObj is TdApi.Error) onResult(false, 0, "Erro sessão: ${meObj.message}")
    }
}

fun baixarFicheiroDaNuvem(context: Context, client: Client, targetFileId: Int, nomeArquivo: String, onResult: (String) -> Unit) {
    if (targetFileId == 0) {
        onResult("ID ausente na nuvem.")
        return
    }
    NexusTransfer.progress.value = 30
    NexusTransfer.label.value = "A descarregar da nuvem..."

    val req = TdApi.DownloadFile().apply {
        fileId = targetFileId
        priority = 32
        synchronous = true
    }

    client.send(req) { res ->
        when (res) {
            is TdApi.File -> {
                val caminhoRemoto = res.local?.path
                if (!caminhoRemoto.isNullOrBlank() && File(caminhoRemoto).exists()) {
                    try {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val destino = File(downloadsDir, "Nexus_${System.currentTimeMillis()}_${limparPrefixoTimestamp(nomeArquivo)}")

                        FileInputStream(File(caminhoRemoto)).use { input ->
                            FileOutputStream(destino).use { output ->
                                input.copyTo(output)
                            }
                        }

                        NexusTransfer.progress.value = null
                        onResult("Salvo em Downloads: ${destino.name}")
                    } catch (e: Exception) {
                        NexusTransfer.progress.value = null
                        onResult("Erro ao gravar ficheiro: ${e.localizedMessage}")
                    }
                } else {
                    NexusTransfer.progress.value = null
                    onResult("Transferência concluída na nuvem.")
                }
            }
            is TdApi.Error -> {
                NexusTransfer.progress.value = null
                onResult("Erro (${res.code}): ${res.message}")
            }
            else -> {
                NexusTransfer.progress.value = null
                onResult("Download em andamento...")
            }
        }
    }
}

fun formatarTamanhoNuvem(bytes: Long?): String {
    val total = bytes ?: 0L
    if (total <= 0L) return "0 MB"
    val mb = total.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1024.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
fun GrelhaArquivos(
    context: Context,
    client: Client,
    vm: UIViewModel,
    lista: List<ArquivoNuvem>,
    mapaPastas: Map<Long, String>,
    onFileClick: (ArquivoNuvem) -> Unit
) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(lista, key = { it.id }) { arq ->
            val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
            val pasta = mapaPastas[arq.dataUpload]
            val thumbSafe = arq.caminhoMiniatura ?: ""
            val dataCurta = formatarDataCurta(arq.dataUpload)

            LaunchedEffect(arq.id, arq.caminhoMiniatura) {
                if (!miniaturaExisteLocalmente(thumbSafe) && arq.fileId != 0) {
                    val req = TdApi.DownloadFile().apply {
                        fileId = arq.fileId
                        priority = 1
                        synchronous = true
                    }
                    client.send(req) { res ->
                        if (res is TdApi.File) {
                            val pathRemoto = res.local?.path ?: ""
                            if (pathRemoto.isNotBlank() && File(pathRemoto).exists()) {
                                val novoThumb = prepararMiniaturaDeFicheiroBaixado(context, File(pathRemoto), arq.nomeArquivo)
                                vm.atualizarCaminhoMiniatura(arq, novoThumb)
                            }
                        }
                    }
                }
            }

            Box(Modifier.aspectRatio(1f).background(SurfaceDark).clickable { onFileClick(arq) }, contentAlignment = Alignment.Center) {
                when {
                    ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || arq.nomeArquivo.contains("IMG", true) -> {
                        if (thumbSafe.isNotBlank()) {
                            AsyncImage(model = Uri.parse(thumbSafe), contentDescription = arq.nomeArquivo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.Image, null, tint = Gold, modifier = Modifier.size(32.dp))
                        }
                    }
                    ext in listOf("MP4", "MKV", "MOV", "AVI") || arq.nomeArquivo.contains("VID", true) || arq.nomeArquivo.contains("Video", true) -> {
                        Box(Modifier.fillMaxSize()) {
                            if (thumbSafe.isNotBlank()) {
                                AsyncImage(model = obterUriCapaMidia(arq.caminhoMiniatura), contentDescription = "Video Preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(44.dp).align(Alignment.Center))
                        }
                    }
                    ext == "PDF" -> {
                        val possuiCapa = remember(arq.caminhoMiniatura) { temCapaPdfLocal(arq.caminhoMiniatura) }
                        if (possuiCapa) {
                            Box(Modifier.fillMaxSize()) {
                                AsyncImage(model = obterUriCapaMidia(arq.caminhoMiniatura), contentDescription = arq.nomeArquivo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).background(DangerRed, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text("PDF", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                                Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    ext in listOf("ZIP", "RAR", "APK") -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(32.dp))
                        Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!pasta.isNullOrBlank()) {
                    Box(Modifier.align(Alignment.TopStart).padding(4.dp).background(BgBlack.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(pasta, color = Gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (dataCurta.isNotBlank()) {
                    Box(Modifier.align(Alignment.BottomStart).padding(4.dp).background(BgBlack.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(dataCurta, color = Color.White, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusUI(dao: NexusDao, client: Client) {
    val vm: UIViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(mClass: Class<T>) = UIViewModel(dao) as T
    })
    val todosArquivos by vm.arquivos.collectAsState()
    val todasPastas by vm.pastas.collectAsState()
    val loginStep by TelegramAuth.step.collectAsState()
    val statusText by TelegramAuth.statusText.collectAsState()
    val errorText by TelegramAuth.errorText.collectAsState()
    val transferProgress by NexusTransfer.progress.collectAsState()
    val transferLabel by NexusTransfer.label.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()

    var phoneNumber by remember { mutableStateOf("+5585") }
    var smsCode by remember { mutableStateOf("") }
    var password2FA by remember { mutableStateOf("") }

    var activeVaultUser by remember { mutableStateOf(obterUltimoUsuarioCofre(context)) }
    var vaultUnlocked by remember { mutableStateOf(false) }
    var isSwitchingUser by remember { mutableStateOf(activeVaultUser.isBlank()) }
    var isCreatingNewVault by remember { mutableStateOf(activeVaultUser.isBlank()) }
    var vaultNameInput by remember { mutableStateOf(activeVaultUser) }
    var vaultPinInput by remember { mutableStateOf("") }
    var vaultErrorMsg by remember { mutableStateOf<String?>(null) }
    var isCheckingVault by remember { mutableStateOf(false) }

    var mapaDonos by remember { mutableStateOf(carregarDonosFicheiros(context)) }
    var mapaPastas by remember { mutableStateOf(carregarVinculosPastas(context)) }
    var pastasDoUsuarioSet by remember(activeVaultUser) { mutableStateOf(carregarPastasDoUsuario(context, activeVaultUser)) }

    var novosFicheirosSync by remember { mutableStateOf<List<NovoArquivoSync>>(emptyList()) }
    var selecionadosSync by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showNovosSyncDialog by remember { mutableStateOf(false) }
    var organizarPorPastaSync by remember { mutableStateOf(true) }

    val arquivos = remember(todosArquivos, mapaDonos, activeVaultUser) {
        val chaveAtiva = normalizarUsuario(activeVaultUser)
        if (chaveAtiva.isBlank()) emptyList()
        else todosArquivos
            .filter { mapaDonos[it.dataUpload] == chaveAtiva }
            .distinctBy { "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}" }
    }

    val pastas = remember(todasPastas, pastasDoUsuarioSet, activeVaultUser) {
        if (activeVaultUser.isBlank()) emptyList()
        else todasPastas
            .filter { p -> pastasDoUsuarioSet.any { it.equals(p.nomePasta.trim(), ignoreCase = true) } }
            .distinctBy { it.nomePasta.trim().lowercase(Locale.ROOT) }
    }

    val armazenamentoUsuario = remember(arquivos) {
        arquivos.sumOf { it.tamanhoBytes }
    }

    fun verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio: Boolean) {
        scope.launch {
            val chaveAtiva = normalizarUsuario(activeVaultUser)
            val listaReal = vm.obterArquivosReais()
            val donosAtuais = carregarDonosFicheiros(context)
            val arquivosAtuaisDoCofre = listaReal.filter { donosAtuais[it.dataUpload] == chaveAtiva }

            arquivosAtuaisDoCofre.forEach { arq ->
                marcarFicheiroComoSincronizado(context, activeVaultUser, arq.nomeArquivo, arq.tamanhoBytes)
            }

            val encontrados = withContext(Dispatchers.IO) {
                procurarNovosFicheirosNasPastas(context, activeVaultUser, arquivosAtuaisDoCofre)
            }
            if (encontrados.isNotEmpty()) {
                novosFicheirosSync = encontrados
                selecionadosSync = encontrados.map { it.uri.toString() }.toSet()
                showNovosSyncDialog = true
            } else if (mostrarAvisoSeVazio) {
                Toast.makeText(context, "Nenhum ficheiro novo nas pastas selecionadas.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(todosArquivos.size, vaultUnlocked) {
        if (todosArquivos.isNotEmpty()) {
            limparDuplicadosExistentes(context, todosArquivos, vm) {
                mapaDonos = carregarDonosFicheiros(context)
                mapaPastas = carregarVinculosPastas(context)
            }
        }
    }

    LaunchedEffect(vaultUnlocked, activeVaultUser) {
        if (vaultUnlocked && activeVaultUser.isNotBlank()) {
            val arquivosIniciais = vm.obterArquivosReais()
            val pastasIniciais = vm.obterPastasReais()
            migrarDadosV11SeNecessario(context, activeVaultUser, arquivosIniciais, pastasIniciais)
            limparDuplicadosExistentes(context, arquivosIniciais, vm) {}
            mapaDonos = carregarDonosFicheiros(context)
            mapaPastas = carregarVinculosPastas(context)
            pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)

            val aposLimpeza = vm.obterArquivosReais()
            sincronizarCofreDaNuvem(context, client, activeVaultUser, aposLimpeza, vm) { qtd ->
                scope.launch(Dispatchers.Main) {
                    if (qtd > 0) {
                        val atualizados = vm.obterArquivosReais()
                        limparDuplicadosExistentes(context, atualizados, vm) {}
                        mapaDonos = carregarDonosFicheiros(context)
                        mapaPastas = carregarVinculosPastas(context)
                        pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                        Toast.makeText(context, "$qtd novo(s) ficheiro(s) sincronizados!", Toast.LENGTH_SHORT).show()
                    }
                    delay(350)
                    verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = false)
                }
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var viewerFile by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var fileToRename by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var novoNomeInput by remember { mutableStateOf("") }

    var fileToDelete by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var isDeletingFile by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    var pastaAberta by remember { mutableStateOf<String?>(null) }
    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fileToMove by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var showStorage by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }

    var pastasSync by remember(activeVaultUser) { mutableStateOf(carregarPastasSync(context, activeVaultUser)) }

    var isUploading by remember { mutableStateOf(false) }
    var cloudBannerText by remember(activeVaultUser) {
        mutableStateOf(if (activeVaultUser.isNotBlank()) "Cofre: $activeVaultUser • Nuvem Ativa" else "Nuvem TDLib Ativa")
    }

    LaunchedEffect(isUploading) {
        while (isUploading) {
            delay(350)
            val atual = NexusTransfer.progress.value ?: 10
            if (atual < 92) NexusTransfer.progress.value = (atual + 12).coerceAtMost(92)
        }
    }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
        }
    }

    fun executarUploads(uris: List<Uri>, pastaEscolhida: String) {
        if (uris.isEmpty() || activeVaultUser.isBlank()) return
        scope.launch {
            isUploading = true
            val total = uris.size
            var enviados = 0

            for ((index, uri) in uris.withIndex()) {
                NexusTransfer.progress.value = 10
                val (nomeBruto, tamanho) = obterDetalhesFicheiro(context, uri)
                val nome = limparPrefixoTimestamp(nomeBruto)
                val loteTag = if (total > 1) "(${index + 1}/$total) " else ""
                NexusTransfer.label.value = "Enviando $loteTag'$nome'..."
                cloudBannerText = "Enviando $loteTag'$nome'..."

                val arqTemp = withContext(Dispatchers.IO) { copiarUriParaCacheTDLib(context, uri, nome) }
                val miniatura = withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, uri, nome) }

                if (arqTemp != null) {
                    val tamFinal = if (tamanho > 0) tamanho else arqTemp.length()
                    desmarcarFicheiroDeletado(context, activeVaultUser, nome, tamFinal)
                    marcarFicheiroComoSincronizado(context, activeVaultUser, nome, tamFinal, uri.toString())

                    val deferred = CompletableDeferred<Triple<Boolean, Int, String>>()
                    enviarParaMensagensSalvas(context, client, activeVaultUser, arqTemp.absolutePath, nome, pastaEscolhida) { ok, rId, msg ->
                        deferred.complete(Triple(ok, rId, msg))
                    }
                    val (ok, rId, msg) = deferred.await()
                    cloudBannerText = msg
                    if (ok) {
                        enviados++
                        val ts = System.currentTimeMillis() + index
                        salvarDonoFicheiro(context, ts, activeVaultUser)
                        mapaDonos = carregarDonosFicheiros(context)
                        if (pastaEscolhida.isNotBlank()) {
                            vm.criarPasta(pastaEscolhida)
                            associarPastaAoUsuario(context, activeVaultUser, pastaEscolhida)
                            pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                            salvarVinculoPasta(context, ts, pastaEscolhida)
                            mapaPastas = carregarVinculosPastas(context)
                        }
                        vm.salvarArquivo(
                            ArquivoNuvem(
                                fileId = rId,
                                nomeArquivo = nome,
                                tamanhoBytes = tamFinal,
                                dataUpload = ts,
                                caminhoMiniatura = miniatura
                            )
                        )
                    }
                } else {
                    cloudBannerText = "Erro ao ler '$nome'."
                }
                delay(120)
            }

            isUploading = false
            NexusTransfer.progress.value = null
            if (total > 1) {
                cloudBannerText = "$enviados de $total ficheiros guardados no cofre de $activeVaultUser!"
            }
        }
    }

    fun executarUploadsNovosSync(itensParaEnviar: List<NovoArquivoSync>, manterPastasOrigem: Boolean) {
        if (itensParaEnviar.isEmpty() || activeVaultUser.isBlank()) return
        scope.launch {
            isUploading = true
            val total = itensParaEnviar.size
            var enviados = 0

            for ((index, item) in itensParaEnviar.withIndex()) {
                val nomeLimpo = limparPrefixoTimestamp(item.nome)
                desmarcarFicheiroDeletado(context, activeVaultUser, nomeLimpo, item.tamanho)
                marcarFicheiroComoSincronizado(context, activeVaultUser, nomeLimpo, item.tamanho, item.uri.toString())

                NexusTransfer.progress.value = 10
                val pastaAlvo = if (manterPastasOrigem) item.nomePastaOrigem.trim() else ""
                val loteTag = if (total > 1) "(${index + 1}/$total) " else ""
                NexusTransfer.label.value = "Sincronizando $loteTag'$nomeLimpo'..."
                cloudBannerText = "Sincronizando $loteTag'$nomeLimpo'..."

                val arqTemp = withContext(Dispatchers.IO) { copiarUriParaCacheTDLib(context, item.uri, nomeLimpo) }
                val miniatura = withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, item.uri, nomeLimpo) }

                if (arqTemp != null) {
                    val tamFinal = if (item.tamanho > 0) item.tamanho else arqTemp.length()
                    marcarFicheiroComoSincronizado(context, activeVaultUser, nomeLimpo, tamFinal, item.uri.toString())

                    val deferred = CompletableDeferred<Triple<Boolean, Int, String>>()
                    enviarParaMensagensSalvas(context, client, activeVaultUser, arqTemp.absolutePath, nomeLimpo, pastaAlvo) { ok, rId, msg ->
                        deferred.complete(Triple(ok, rId, msg))
                    }
                    val (ok, rId, msg) = deferred.await()
                    cloudBannerText = msg
                    if (ok) {
                        enviados++
                        val ts = System.currentTimeMillis() + index
                        salvarDonoFicheiro(context, ts, activeVaultUser)
                        mapaDonos = carregarDonosFicheiros(context)
                        if (pastaAlvo.isNotBlank()) {
                            vm.criarPasta(pastaAlvo)
                            associarPastaAoUsuario(context, activeVaultUser, pastaAlvo)
                            pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                            salvarVinculoPasta(context, ts, pastaAlvo)
                            mapaPastas = carregarVinculosPastas(context)
                        }
                        vm.salvarArquivo(
                            ArquivoNuvem(
                                fileId = rId,
                                nomeArquivo = nomeLimpo,
                                tamanhoBytes = tamFinal,
                                dataUpload = ts,
                                caminhoMiniatura = miniatura
                            )
                        )
                    }
                }
                delay(120)
            }

            isUploading = false
            NexusTransfer.progress.value = null
            cloudBannerText = "$enviados novo(s) ficheiro(s) sincronizados com sucesso!"
            Toast.makeText(context, cloudBannerText, Toast.LENGTH_SHORT).show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                try { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            if (pastaAberta != null) executarUploads(uris, pastaAberta!!)
            else if (pastas.isNotEmpty()) pendingUploadUris = uris
            else executarUploads(uris, "")
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                salvarPastaSync(context, activeVaultUser, it.toString(), true)
                pastasSync = carregarPastasSync(context, activeVaultUser)
                verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = false)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao guardar permissão da pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 1. ECRÃ DE AUTENTICAÇÃO DO NÚMERO TELEGRAM (TDLIB)
    if (loginStep < 3) {
        Column(Modifier.fillMaxSize().background(BgBlack).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Cloud, null, tint = Gold, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(statusText, color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (errorText != null) Text(errorText!!, color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(32.dp))
            when (loginStep) {
                0 -> {
                    OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("Número (+5585...)", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        TelegramAuth.errorText.value = null
                        val clean = phoneNumber.replace(" ", "").replace("-", "")
                        val cfg = TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())
                        client.send(TdApi.SetAuthenticationPhoneNumber(clean, cfg), responseHandler)
                    }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENVIAR CÓDIGO", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                1 -> {
                    OutlinedTextField(value = smsCode, onValueChange = { smsCode = it }, label = { Text("Código SMS/Telegram", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationCode(smsCode.trim()), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                2 -> {
                    OutlinedTextField(value = password2FA, onValueChange = { password2FA = it }, label = { Text("Senha 2FA", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationPassword(password2FA), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENTRAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    // 2. ECRÃ DE COFRE INDIVIDUAL POR ETIQUETA DE PERFIL + PIN
    if (!vaultUnlocked) {
        Column(
            modifier = Modifier.fillMaxSize().background(BgBlack).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Black)

                    if (!isSwitchingUser && activeVaultUser.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "COFRE DE ${activeVaultUser.uppercase(Locale.getDefault())}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Digite o seu PIN para desbloquear os seus ficheiros",
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(20.dp))

                        OutlinedTextField(
                            value = vaultPinInput,
                            onValueChange = { if (it.length <= 6) vaultPinInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("PIN de Acesso", color = TextGrey) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (vaultErrorMsg != null) {
                            Text(
                                vaultErrorMsg!!,
                                color = DangerRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                vaultErrorMsg = null
                                val pinDigitado = vaultPinInput.trim()
                                if (pinDigitado.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }
                                val pinLocal = obterPinCofreLocal(context, activeVaultUser)
                                if (pinLocal != null) {
                                    if (pinLocal == pinDigitado) {
                                        vaultPinInput = ""
                                        vaultUnlocked = true
                                    } else {
                                        vaultErrorMsg = "PIN incorreto!"
                                    }
                                } else {
                                    isCheckingVault = true
                                    verificarOuRegistarCofreNuvem(client, activeVaultUser, pinDigitado, false) { ok, nomeReal, msg ->
                                        scope.launch(Dispatchers.Main) {
                                            isCheckingVault = false
                                            if (ok) {
                                                salvarCredenciaisCofreLocal(context, nomeReal, pinDigitado)
                                                activeVaultUser = nomeReal
                                                vaultPinInput = ""
                                                vaultUnlocked = true
                                            } else {
                                                vaultErrorMsg = msg
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingVault,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isCheckingVault) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Text("DESBLOQUEAR COFRE", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            vaultErrorMsg = null
                            vaultPinInput = ""
                            vaultNameInput = ""
                            isSwitchingUser = true
                            isCreatingNewVault = false
                        }) {
                            Text("Trocar de utilizador / Criar novo cofre", color = Gold, fontSize = 12.sp)
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { isCreatingNewVault = true; vaultErrorMsg = null },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isCreatingNewVault) Gold else SurfaceDark),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("CRIAR COFRE", color = if (isCreatingNewVault) BgBlack else TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { isCreatingNewVault = false; vaultErrorMsg = null },
                                colors = ButtonDefaults.buttonColors(containerColor = if (!isCreatingNewVault) Gold else SurfaceDark),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("JÁ TENHO COFRE", color = if (!isCreatingNewVault) BgBlack else TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (isCreatingNewVault) "Escolha um Nome único e um PIN de 4 dígitos para isolar os seus ficheiros."
                            else "Digite o seu Nome e PIN para carregar os seus ficheiros enviados anteriormente.",
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = vaultNameInput,
                            onValueChange = { vaultNameInput = it },
                            label = { Text("Nome do Utilizador (ex: Carlos_85)", color = TextGrey) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Person, null, tint = Gold) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vaultPinInput,
                            onValueChange = { if (it.length <= 6) vaultPinInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("PIN (4 a 6 dígitos)", color = TextGrey) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Gold) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (vaultErrorMsg != null) {
                            Text(
                                vaultErrorMsg!!,
                                color = DangerRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                vaultErrorMsg = null
                                val nomeLimpo = vaultNameInput.trim()
                                val pinLimpo = vaultPinInput.trim()

                                if (nomeLimpo.length < 3) {
                                    vaultErrorMsg = "O nome deve ter pelo menos 3 caracteres."
                                    return@Button
                                }
                                if (pinLimpo.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }

                                val pinLocalExistente = obterPinCofreLocal(context, nomeLimpo)
                                if (isCreatingNewVault && pinLocalExistente != null) {
                                    vaultErrorMsg = "Este nome já está registado neste aparelho! Escolha outro nome."
                                    return@Button
                                }

                                isCheckingVault = true
                                verificarOuRegistarCofreNuvem(client, nomeLimpo, pinLimpo, isCreatingNewVault) { ok, nomeConfirmado, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        isCheckingVault = false
                                        if (ok) {
                                            salvarCredenciaisCofreLocal(context, nomeConfirmado, pinLimpo)
                                            activeVaultUser = nomeConfirmado
                                            isSwitchingUser = false
                                            vaultPinInput = ""
                                            vaultUnlocked = true
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        } else {
                                            vaultErrorMsg = msg
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingVault,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isCheckingVault) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Text(
                                    if (isCreatingNewVault) "CRIAR E ABRIR COFRE" else "ENTRAR E RESTAURAR COFRE",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (activeVaultUser.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                vaultErrorMsg = null
                                vaultPinInput = ""
                                isSwitchingUser = false
                            }) {
                                Text("Voltar para o cofre de $activeVaultUser", color = TextGrey, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // JANELA DE CONFIRMAÇÃO PARA DELETAR / REMOVER FICHEIRO (v1.3)
    if (fileToDelete != null) {
        val arqD = fileToDelete!!
        Dialog(onDismissRequest = { if (!isDeletingFile) fileToDelete = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, DangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteForever, null, tint = DangerRed, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("REMOVER OU DELETAR?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        arqD.nomeArquivo,
                        color = Gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Escolha se deseja apagar também das Mensagens Salvas do Telegram ou remover apenas da galeria do aplicativo.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            isDeletingFile = true
                            eliminarFicheiroCompleto(context, client, activeVaultUser, arqD, true, vm) { msg ->
                                scope.launch(Dispatchers.Main) {
                                    isDeletingFile = false
                                    mapaPastas = carregarVinculosPastas(context)
                                    mapaDonos = carregarDonosFicheiros(context)
                                    if (viewerFile?.id == arqD.id) viewerFile = null
                                    fileToDelete = null
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDeletingFile,
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (isDeletingFile) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.DeleteForever, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("DELETAR TUDO (APP + NUVEM)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            isDeletingFile = true
                            eliminarFicheiroCompleto(context, client, activeVaultUser, arqD, false, vm) { msg ->
                                scope.launch(Dispatchers.Main) {
                                    isDeletingFile = false
                                    mapaPastas = carregarVinculosPastas(context)
                                    mapaDonos = carregarDonosFicheiros(context)
                                    if (viewerFile?.id == arqD.id) viewerFile = null
                                    fileToDelete = null
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDeletingFile,
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("REMOVER SÓ DO APP", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { if (!isDeletingFile) fileToDelete = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CANCELAR", color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // JANELA AUTOMÁTICA DE NOVOS FICHEIROS NAS PASTAS DE SINCRONIZAÇÃO (v1.3)
    if (showNovosSyncDialog && novosFicheirosSync.isNotEmpty()) {
        Dialog(onDismissRequest = { showNovosSyncDialog = false }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = Gold, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NOVOS FICHEIROS", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(
                                "${novosFicheirosSync.size} ficheiro(s) novo(s) nas pastas sincronizadas",
                                color = TextGrey,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selecionadosSync = if (selecionadosSync.size == novosFicheirosSync.size) {
                                emptySet()
                            } else {
                                novosFicheirosSync.map { it.uri.toString() }.toSet()
                            }
                        }) {
                            Text(
                                if (selecionadosSync.size == novosFicheirosSync.size) "Desmarcar todos" else "Selecionar todos",
                                color = Gold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = organizarPorPastaSync,
                                onCheckedChange = { organizarPorPastaSync = it },
                                colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = BgBlack)
                            )
                            Text("Manter pasta", color = Color.White, fontSize = 11.sp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        novosFicheirosSync.forEach { item ->
                            val chaveUri = item.uri.toString()
                            val marcado = selecionadosSync.contains(chaveUri)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selecionadosSync = if (marcado) selecionadosSync - chaveUri else selecionadosSync + chaveUri
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = marcado,
                                    onCheckedChange = {
                                        selecionadosSync = if (it) selecionadosSync + chaveUri else selecionadosSync - chaveUri
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = BgBlack)
                                )
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text(
                                        item.nome,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "📁 ${item.nomePastaOrigem} • ${formatarTamanhoNuvem(item.tamanho)}",
                                        color = TextGrey,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showNovosSyncDialog = false },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("AGORA NÃO", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val escolhidos = novosFicheirosSync.filter { selecionadosSync.contains(it.uri.toString()) }
                                showNovosSyncDialog = false
                                executarUploadsNovosSync(escolhidos, organizarPorPastaSync)
                            },
                            enabled = selecionadosSync.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1.3f).height(48.dp)
                        ) {
                            Text(
                                "UPLOAD (${selecionadosSync.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (fileToRename != null) {
        val arqR = fileToRename!!
        val extOriginal = arqR.nomeArquivo.substringAfterLast('.', "")
        Dialog(onDismissRequest = { fileToRename = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("RENOMEAR FICHEIRO", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    if (extOriginal.isNotBlank()) {
                        Text("Formato mantido automaticamente: .$extOriginal", color = TextGrey, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = novoNomeInput,
                        onValueChange = { novoNomeInput = it },
                        label = { Text("Novo nome", color = TextGrey) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { fileToRename = null },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCELAR", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val limpo = novoNomeInput.trim()
                                if (limpo.isNotBlank()) {
                                    val nomeFinal = if (extOriginal.isNotBlank() && !limpo.endsWith(".$extOriginal", ignoreCase = true)) {
                                        "$limpo.$extOriginal"
                                    } else {
                                        limpo
                                    }
                                    vm.renomearArquivo(arqR, nomeFinal)
                                    marcarFicheiroComoSincronizado(context, activeVaultUser, nomeFinal, arqR.tamanhoBytes)
                                    if (viewerFile?.dataUpload == arqR.dataUpload) {
                                        viewerFile = arqR.copy(nomeArquivo = nomeFinal)
                                    }
                                    Toast.makeText(context, "Renomeado para $nomeFinal", Toast.LENGTH_SHORT).show()
                                }
                                fileToRename = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SALVAR", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (pendingUploadUris.isNotEmpty()) {
        val listaAtual = pendingUploadUris
        Dialog(onDismissRequest = { pendingUploadUris = emptyList() }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("GUARDAR ${listaAtual.size} FICHEIRO(S)", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { pendingUploadUris = emptyList(); executarUploads(listaAtual, "") }, border = BorderStroke(1.dp, TextGrey), modifier = Modifier.fillMaxWidth()) {
                        Text("Galeria Geral (Sem pasta)", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    pastas.forEach { p ->
                        Button(onClick = { pendingUploadUris = emptyList(); executarUploads(listaAtual, p.nomePasta) }, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (fileToMove != null) {
        val arqM = fileToMove!!
        Dialog(onDismissRequest = { fileToMove = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("MOVER PARA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { salvarVinculoPasta(context, arqM.dataUpload, ""); mapaPastas = carregarVinculosPastas(context); fileToMove = null }, border = BorderStroke(1.dp, TextGrey), modifier = Modifier.fillMaxWidth()) {
                        Text("Remover da Pasta", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    pastas.forEach { p ->
                        Button(onClick = { salvarVinculoPasta(context, arqM.dataUpload, p.nomePasta); mapaPastas = carregarVinculosPastas(context); fileToMove = null }, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (viewerFile != null) {
        val cur = viewerFile!!
        val curThumb = cur.caminhoMiniatura ?: ""
        val ext = cur.nomeArquivo.substringAfterLast('.', "").uppercase()
        val isImage = ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || cur.nomeArquivo.contains("IMG", true)
        val isVideo = ext in listOf("MP4", "MKV", "MOV", "AVI") || cur.nomeArquivo.contains("VID", true) || cur.nomeArquivo.contains("Video", true)
        val isPdf = ext == "PDF"
        val isText = ext in listOf("TXT", "CSV", "LOG", "JSON", "XML", "MD")

        var videoViewRef by remember { mutableStateOf<android.widget.VideoView?>(null) }
        var isPlaying by remember { mutableStateOf(true) }
        var currentPos by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }

        var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var isLoadingDoc by remember { mutableStateOf(false) }
        var textContent by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(cur.caminhoMiniatura, ext) {
            if (isPdf) {
                isLoadingDoc = true
                pdfPages = withContext(Dispatchers.IO) { renderizarPaginasPdf(cur.caminhoMiniatura) }
                isLoadingDoc = false
            } else if (isText) {
                isLoadingDoc = true
                textContent = withContext(Dispatchers.IO) { lerTextoDocumento(cur.caminhoMiniatura) }
                isLoadingDoc = false
            }
        }

        LaunchedEffect(videoViewRef, isPlaying) {
            while (videoViewRef != null) {
                try {
                    val vv = videoViewRef!!
                    if (vv.isPlaying) {
                        currentPos = vv.currentPosition
                        val dur = vv.duration
                        if (dur > 0) duration = dur
                    }
                    isPlaying = vv.isPlaying
                } catch (_: Exception) {}
                delay(250)
            }
        }

        Dialog(onDismissRequest = { viewerFile = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when {
                    isImage -> {
                        AsyncImage(model = Uri.parse(curThumb), contentDescription = cur.nomeArquivo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    isVideo -> {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    setVideoURI(Uri.parse(curThumb))
                                    setOnPreparedListener { mp ->
                                        duration = mp.duration.coerceAtLeast(0)
                                        start()
                                        isPlaying = true
                                    }
                                    setOnCompletionListener {
                                        isPlaying = false
                                        currentPos = duration
                                    }
                                    videoViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize().align(Alignment.Center)
                        )
                    }
                    isPdf -> {
                        if (isLoadingDoc) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Gold)
                                Spacer(Modifier.height(12.dp))
                                Text("A renderizar páginas do PDF...", color = Color.White, fontSize = 13.sp)
                            }
                        } else if (pdfPages.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 72.dp, bottom = 72.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                pdfPages.forEachIndexed { idx, paginaBmp ->
                                    Text(
                                        "Página ${idx + 1} de ${pdfPages.size}",
                                        color = Gold,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                    Image(
                                        bitmap = paginaBmp.asImageBitmap(),
                                        contentDescription = "Página ${idx + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White, RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        } else {
                            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(20.dp))
                                Button(
                                    onClick = { abrirFicheiroExterno(context, cur.caminhoMiniatura, cur.nomeArquivo) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                                ) {
                                    Icon(Icons.Default.OpenInNew, null, tint = BgBlack)
                                    Spacer(Modifier.width(8.dp))
                                    Text("ABRIR NO LEITOR DE PDF", color = BgBlack, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    isText && !textContent.isNullOrBlank() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 76.dp, bottom = 76.dp, start = 16.dp, end = 16.dp)
                                .background(SurfaceDark, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(textContent!!, color = Color.White, fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(72.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text("Tamanho: ${formatarTamanhoNuvem(cur.tamanhoBytes)}", color = TextGrey, fontSize = 12.sp)
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { abrirFicheiroExterno(context, cur.caminhoMiniatura, cur.nomeArquivo) },
                                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, null, tint = BgBlack)
                                Spacer(Modifier.width(8.dp))
                                Text("ABRIR DOCUMENTO", color = BgBlack, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().background(BgBlack.copy(alpha = 0.85f)).padding(12.dp).align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewerFile = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(cur.nomeArquivo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatarDataNuvem(cur.dataUpload), color = TextGrey, fontSize = 10.sp, maxLines = 1)
                    }
                    IconButton(onClick = {
                        novoNomeInput = cur.nomeArquivo.substringBeforeLast('.')
                        fileToRename = cur
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = Color.White)
                    }
                    IconButton(onClick = { compartilharFicheiroNativo(context, cur.caminhoMiniatura, cur.nomeArquivo) }) {
                        Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = Gold)
                    }
                    IconButton(onClick = { fileToDelete = cur }) {
                        Icon(Icons.Default.Delete, contentDescription = "Deletar", tint = DangerRed)
                    }
                }

                if (!isImage && !isVideo) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgBlack.copy(alpha = 0.9f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = { abrirFicheiroExterno(context, cur.caminhoMiniatura, cur.nomeArquivo) },
                            border = BorderStroke(1.dp, Gold),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, null, tint = Gold)
                            Spacer(Modifier.width(8.dp))
                            Text("ABRIR EM APP EXTERNA", color = Gold, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (isVideo) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgBlack.copy(alpha = 0.85f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                            onValueChange = { novoValor ->
                                val pos = novoValor.toInt()
                                currentPos = pos
                                videoViewRef?.seekTo(pos)
                            },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Gold,
                                activeTrackColor = Gold,
                                inactiveTrackColor = SurfaceDark
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatarTempoVideo(currentPos), color = TextGrey, fontSize = 11.sp)
                            Text(formatarTempoVideo(duration), color = TextGrey, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        val novo = (vv.currentPosition - 10000).coerceAtLeast(0)
                                        vv.seekTo(novo)
                                        currentPos = novo
                                    }
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.FastRewind, null, tint = Gold, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("-10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            FloatingActionButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        if (vv.isPlaying) {
                                            vv.pause()
                                            isPlaying = false
                                        } else {
                                            vv.start()
                                            isPlaying = true
                                        }
                                    }
                                },
                                containerColor = Gold,
                                contentColor = BgBlack,
                                shape = CircleShape,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        val maxDur = if (duration > 0) duration else vv.duration.coerceAtLeast(0)
                                        val novo = (vv.currentPosition + 10000).coerceAtMost(maxDur)
                                        vv.seekTo(novo)
                                        currentPos = novo
                                    }
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("+10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.FastForward, null, tint = Gold, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(300.dp)) {
                Column(Modifier.fillMaxWidth().background(SurfaceDarker).padding(32.dp)) {
                    Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cofre: $activeVaultUser", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Nexus Cloud $NEXUS_VERSION • $phoneNumber", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Storage, null) },
                    label = { Text("Armazenamento do Cofre") },
                    selected = false,
                    onClick = { showStorage = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Sync, null) },
                    label = { Text("Sincronização Auto") },
                    selected = false,
                    onClick = { showSync = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    label = { Text("Restaurar Cofre da Nuvem") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            cloudBannerText = "A procurar ficheiros de $activeVaultUser na nuvem..."
                            val atuaisReais = vm.obterArquivosReais()
                            sincronizarCofreDaNuvem(context, client, activeVaultUser, atuaisReais, vm) { qtd ->
                                scope.launch(Dispatchers.Main) {
                                    val aposSync = vm.obterArquivosReais()
                                    limparDuplicadosExistentes(context, aposSync, vm) {}
                                    mapaDonos = carregarDonosFicheiros(context)
                                    mapaPastas = carregarVinculosPastas(context)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                    cloudBannerText = if (qtd > 0) "$qtd ficheiro(s) restaurados da nuvem!" else "Cofre de $activeVaultUser atualizado!"
                                    Toast.makeText(context, cloudBannerText, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Lock, null) },
                    label = { Text("Trancar / Trocar Cofre") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        pastaAberta = null
                        vaultPinInput = ""
                        vaultErrorMsg = null
                        isSwitchingUser = false
                        vaultUnlocked = false
                    },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = DangerRed, unselectedIconColor = DangerRed)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(Modifier.background(BgBlack)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(if (transferProgress != null) transferLabel else cloudBannerText, color = if (transferProgress != null) Gold else TextGrey, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) searchQuery = ""
                        }) {
                            Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, contentDescription = "Pesquisar", tint = Gold)
                        }
                        if (transferProgress != null || isUploading) {
                            Text("${transferProgress ?: 10}%", color = Gold, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            IconButton(onClick = {
                                vaultPinInput = ""
                                vaultErrorMsg = null
                                isSwitchingUser = false
                                vaultUnlocked = false
                            }) {
                                Icon(Icons.Default.Lock, contentDescription = "Trancar Cofre", tint = Gold)
                            }
                        }
                    }
                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Pesquisar no cofre de $activeVaultUser...", color = TextGrey, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Gold) },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, null, tint = TextGrey)
                                    }
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = SurfaceDark,
                                focusedContainerColor = SurfaceDarker,
                                unfocusedContainerColor = SurfaceDarker
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                    if (transferProgress != null || isUploading) {
                        val fra = ((transferProgress ?: 10).coerceIn(5, 100)) / 100f
                        Box(Modifier.fillMaxWidth().height(4.dp).background(SurfaceDark)) {
                            Box(Modifier.fillMaxWidth(fra).height(4.dp).background(Gold))
                        }
                    }
                    TabRow(selectedTabIndex = selectedTab, containerColor = BgBlack, indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) }, divider = {}) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "VÍDEOS", "DOCS").forEachIndexed { i, t ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i; if (i != 1) pastaAberta = null }) {
                                Text(t, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }, containerColor = DangerRed, contentColor = Color.White, shape = CircleShape, modifier = Modifier.shadow(16.dp, CircleShape)) {
                    Icon(Icons.Default.CloudUpload, null)
                }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 1) {
                    if (pastaAberta != null) {
                        val daPasta = remember(arquivos, mapaPastas, pastaAberta, searchQuery) {
                            arquivos.filter {
                                mapaPastas[it.dataUpload]?.equals(pastaAberta, ignoreCase = true) == true &&
                                    (searchQuery.isBlank() || it.nomeArquivo.contains(searchQuery, ignoreCase = true))
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().background(SurfaceDarker).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { pastaAberta = null }) { Icon(Icons.Default.ArrowBack, null, tint = Gold) }
                                Text(pastaAberta!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            GrelhaArquivos(context, client, vm, daPasta, mapaPastas) { selectedFile = it }
                        }
                    } else {
                        val pastasFiltradas = remember(pastas, searchQuery) {
                            if (searchQuery.isBlank()) pastas
                            else pastas.filter { it.nomePasta.contains(searchQuery, ignoreCase = true) }
                        }
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            OutlinedButton(onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(16.dp))
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pastasFiltradas, key = { it.nomePasta.trim().lowercase(Locale.ROOT) }) { p ->
                                    val qtd = arquivos.count { mapaPastas[it.dataUpload]?.equals(p.nomePasta, ignoreCase = true) == true }
                                    Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(12.dp)).clickable { pastaAberta = p.nomePasta }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                                            Text("$qtd item(ns)", color = TextGrey, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val filtrada = remember(arquivos, selectedTab, searchQuery) {
                        val base = when (selectedTab) {
                            2 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || it.nomeArquivo.contains("IMG", true) }
                            3 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("MP4", "MKV", "MOV", "AVI") || it.nomeArquivo.contains("VID", true) || it.nomeArquivo.contains("Video", true) }
                            4 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("PDF", "ZIP", "RAR", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX", "TXT", "CSV", "JSON", "XML", "MD", "APK") }
                            else -> arquivos
                        }
                        if (searchQuery.isBlank()) base
                        else base.filter { it.nomeArquivo.contains(searchQuery, ignoreCase = true) }
                    }
                    GrelhaArquivos(context, client, vm, filtrada, mapaPastas) { selectedFile = it }
                }
            }

            if (selectedFile != null) {
                val arq = selectedFile!!
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(arq.nomeArquivo, color = Gold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontWeight = FontWeight.Bold)
                        Text("Cofre: $activeVaultUser • Tamanho: ${formatarTamanhoNuvem(arq.tamanhoBytes)} • Pasta: ${mapaPastas[arq.dataUpload] ?: "Geral"}\nAdicionado em: ${formatarDataNuvem(arq.dataUpload)}", color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                        ListItem(
                            headlineContent = { Text("Abrir no Nexus Cloud", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Fullscreen, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { viewerFile = arqPronto }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Abrir em App Externa...", color = Gold) },
                            leadingContent = { Icon(Icons.Default.OpenInNew, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { abrirFicheiroExterno(context, arqPronto.caminhoMiniatura, arqPronto.nomeArquivo) }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(headlineContent = { Text("Renomear ficheiro...", color = Color.White) }, leadingContent = { Icon(Icons.Default.Edit, null, tint = Color.White) }, modifier = Modifier.clickable { novoNomeInput = arq.nomeArquivo.substringBeforeLast('.'); fileToRename = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Mover para Pasta...", color = Gold) }, leadingContent = { Icon(Icons.Default.Folder, null, tint = Gold) }, modifier = Modifier.clickable { fileToMove = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(
                            headlineContent = { Text("Compartilhar...", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { compartilharFicheiroNativo(context, arqPronto.caminhoMiniatura, arqPronto.nomeArquivo) }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(headlineContent = { Text("Baixar da Nuvem", color = Gold) }, leadingContent = { Icon(Icons.Default.CloudDownload, null, tint = Gold) }, modifier = Modifier.clickable { selectedFile = null; baixarFicheiroDaNuvem(context, client, arq.fileId, arq.nomeArquivo) { m -> scope.launch(Dispatchers.Main) { Toast.makeText(context, m, Toast.LENGTH_LONG).show() } } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(
                            headlineContent = { Text("Deletar / Remover ficheiro...", color = DangerRed, fontWeight = FontWeight.Bold) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable {
                                fileToDelete = arq
                                selectedFile = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            if (showNewFolder) {
                Dialog(onDismissRequest = { showNewFolder = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp)) {
                            Text("NOVA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(value = nomeNovaPasta, onValueChange = { nomeNovaPasta = it }, label = { Text("Nome", color = TextGrey) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                val n = nomeNovaPasta.trim()
                                if (n.isNotBlank()) {
                                    vm.criarPasta(n)
                                    associarPastaAoUsuario(context, activeVaultUser, n)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                }
                                nomeNovaPasta = ""
                                showNewFolder = false
                            }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("CRIAR", color = Color.White) }
                        }
                    }
                }
            }

            if (showStorage) {
                Dialog(onDismissRequest = { showStorage = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("USO DO COFRE: ${activeVaultUser.uppercase(Locale.getDefault())}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(formatarTamanhoNuvem(armazenamentoUsuario), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                            Text("${arquivos.size} ficheiro(s) neste perfil", color = TextGrey, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(Gold, DangerRed)), RoundedCornerShape(3.dp)))
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { showStorage = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("FECHAR", color = Color.White) }
                        }
                    }
                }
            }

            if (showSync) {
                Dialog(onDismissRequest = { showSync = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("BACKUP AUTOMÁTICO ($activeVaultUser)", color = Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text("Ao abrir o cofre, a app verifica estas pastas e mostra os ficheiros novos para upload.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))

                            if (pastasSync.isEmpty()) {
                                Text("Nenhuma pasta selecionada.", color = TextGrey, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                Column(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    pastasSync.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { removerPastaSync(context, activeVaultUser, p.uri); pastasSync = carregarPastasSync(context, activeVaultUser) }) {
                                                Icon(Icons.Default.Delete, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                                            }
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(p.nome, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Switch(
                                                checked = p.ativa,
                                                onCheckedChange = { salvarPastaSync(context, activeVaultUser, p.uri, it); pastasSync = carregarPastasSync(context, activeVaultUser) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DangerRed, uncheckedThumbColor = TextGrey, uncheckedTrackColor = SurfaceDark)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { folderPicker.launch(null) }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                                Icon(Icons.Default.CreateNewFolder, null, tint = Gold)
                                Spacer(Modifier.width(8.dp))
                                Text("ADICIONAR PASTA", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            if (pastasSync.any { it.ativa }) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        showSync = false
                                        verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = true)
                                    },
                                    border = BorderStroke(1.dp, Color.White),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Icon(Icons.Default.Search, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("PROCURAR NOVOS AGORA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showSync = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                                Text("CONCLUÍDO", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
// === FIM DO FICHEIRO ===
