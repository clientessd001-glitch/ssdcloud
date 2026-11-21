package com.seu.nexuscloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResultadoAnaliseIA(
    val textoCompleto: String,
    val categoria: String,
    val codigosPix: List<String>,
    val boletos: List<String>,
    val valores: List<String>,
    val datas: List<String>,
    val documentos: List<String>
)

object NexusAI {
    private const val PREFS_OCR = "nexus_ai_ocr_index_v1"
    private val cacheMemoria = mutableMapOf<Long, String>()
    private var inicializado = false

    fun inicializar(context: Context) {
        if (inicializado) return
        inicializado = true
        val prefs = context.getSharedPreferences(PREFS_OCR, Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            if (k.startsWith("ocr_") && v is String && v.isNotBlank()) {
                val ts = k.removePrefix("ocr_").toLongOrNull()
                if (ts != null) cacheMemoria[ts] = v
            }
        }
    }

    fun obterTextoIndexado(context: Context, dataUpload: Long): String {
        inicializar(context)
        return cacheMemoria[dataUpload] ?: ""
    }

    fun salvarTextoIndexado(context: Context, dataUpload: Long, texto: String) {
        val limpo = texto.trim().take(12000)
        if (limpo.isBlank()) return
        inicializar(context)
        cacheMemoria[dataUpload] = limpo
        context.getSharedPreferences(PREFS_OCR, Context.MODE_PRIVATE)
            .edit()
            .putString("ocr_$dataUpload", limpo)
            .apply()
    }

    fun removerIndice(context: Context, dataUpload: Long) {
        cacheMemoria.remove(dataUpload)
        context.getSharedPreferences(PREFS_OCR, Context.MODE_PRIVATE)
            .edit()
            .remove("ocr_$dataUpload")
            .apply()
    }

    fun totalIndexados(context: Context): Int {
        inicializar(context)
        return cacheMemoria.size
    }

    fun normalizarBusca(texto: String): String {
        val semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return semAcento.lowercase(Locale.ROOT).trim()
    }

    fun correspondeBuscaIA(context: Context, dataUpload: Long, consulta: String): Boolean {
        if (consulta.isBlank()) return false
        val texto = obterTextoIndexado(context, dataUpload)
        if (texto.isBlank()) return false
        val consultaNorm = normalizarBusca(consulta)
        val textoNorm = normalizarBusca(texto)
        val termos = consultaNorm.split(Regex("\\s+")).filter { it.isNotBlank() }
        return termos.isNotEmpty() && termos.all { textoNorm.contains(it) }
    }

    fun suportaLeituraIA(nomeArquivo: String): Boolean {
        val ext = nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
        return ehArquivoImagem(nomeArquivo) ||
            ext == "PDF" ||
            ext in listOf("TXT", "CSV", "LOG", "JSON", "XML", "MD")
    }

    private suspend fun reconhecerBitmapMLKit(bitmap: Bitmap): String {
        val def = CompletableDeferred<String>()
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    def.complete(visionText.text ?: "")
                }
                .addOnFailureListener {
                    def.complete("")
                }
        } catch (_: Throwable) {
            def.complete("")
        }
        return def.await()
    }

    private fun decodificarBitmapSeguro(context: Context, caminho: String): Bitmap? {
        return try {
            if (caminho.startsWith("content://")) {
                val uri = Uri.parse(caminho)
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, optsBounds)
                }
                var sample = 1
                while (optsBounds.outWidth / sample > 1600 || optsBounds.outHeight / sample > 1600) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } else {
                val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
                val arq = File(limpo)
                if (!arq.exists() || arq.length() < 128L) return null
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(arq.absolutePath, optsBounds)
                var sample = 1
                while (optsBounds.outWidth / sample > 1600 || optsBounds.outHeight / sample > 1600) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                BitmapFactory.decodeFile(arq.absolutePath, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun extrairTextoDeArquivo(
        context: Context,
        caminhoArquivo: String?,
        nomeArquivo: String,
        dataUpload: Long,
        forcarReleitura: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (!forcarReleitura) {
            val salvo = obterTextoIndexado(context, dataUpload)
            if (salvo.isNotBlank()) return@withContext salvo
        }

        val caminho = caminhoArquivo ?: return@withContext ""
        if (caminho.isBlank()) return@withContext ""

        val ext = nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val textoExtraido = when {
            ext in listOf("TXT", "CSV", "LOG", "JSON", "XML", "MD") -> {
                lerTextoDocumento(caminho) ?: ""
            }
            ext == "PDF" -> {
                val sb = StringBuilder()
                try {
                    val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
                    val arqPdf = File(limpo)
                    if (arqPdf.exists() && !limpo.endsWith("_frame.jpg")) {
                        ParcelFileDescriptor.open(arqPdf, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                val totalPaginas = renderer.pageCount.coerceAtMost(5)
                                for (i in 0 until totalPaginas) {
                                    renderer.openPage(i).use { page ->
                                        val largura = (page.width * 2).coerceIn(600, 1400)
                                        val altura = (page.height * 2).coerceIn(800, 1800)
                                        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                                        bmp.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        val txtPag = reconhecerBitmapMLKit(bmp)
                                        bmp.recycle()
                                        if (txtPag.isNotBlank()) {
                                            if (sb.isNotEmpty()) sb.append("\n\n")
                                            sb.append(txtPag.trim())
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val bmp = decodificarBitmapSeguro(context, caminho)
                        if (bmp != null) {
                            sb.append(reconhecerBitmapMLKit(bmp))
                            bmp.recycle()
                        }
                    }
                } catch (_: Throwable) {}
                sb.toString().trim()
            }
            ehArquivoImagem(nomeArquivo) -> {
                val bmp = decodificarBitmapSeguro(context, caminho)
                if (bmp != null) {
                    val txt = reconhecerBitmapMLKit(bmp)
                    bmp.recycle()
                    txt.trim()
                } else ""
            }
            else -> ""
        }

        if (textoExtraido.isNotBlank()) {
            salvarTextoIndexado(context, dataUpload, textoExtraido)
        }
        return@withContext textoExtraido
    }

    fun analisarConteudo(texto: String, nomeArquivo: String): ResultadoAnaliseIA {
        val compacto = texto.replace("\r", "")
        val semQuebras = compacto.replace("\n", "")
        val norm = normalizarBusca(compacto)

        // 1. Códigos PIX (Copia e Cola EMV + Chaves aleatórias UUID + E-mail em contexto PIX)
        val pixEncontrados = mutableListOf<String>()
        Regex("000201[A-Za-z0-9.\\-/@:+* _]{30,260}").findAll(semQuebras).forEach {
            val limpo = it.value.replace(" ", "").trim()
            if (limpo.length >= 35) pixEncontrados.add(limpo)
        }
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").findAll(compacto).forEach {
            pixEncontrados.add(it.value.trim())
        }
        if (norm.contains("pix") || norm.contains("chave")) {
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").findAll(compacto).forEach {
                pixEncontrados.add(it.value.trim())
            }
        }

        // 2. Linhas digitáveis de boletos bancários e contas de consumo
        val boletosEncontrados = mutableListOf<String>()
        Regex("\\d{5}[.\\s]?\\d{5}[\\s.]+\\d{5}[.\\s]?\\d{6}[\\s.]+\\d{5}[.\\s]?\\d{6}[\\s.]+\\d[\\s.]+\\d{14}")
            .findAll(compacto).forEach { boletosEncontrados.add(it.value.trim()) }
        Regex("\\d{11}[-\\s]?\\d[\\s]+\\d{11}[-\\s]?\\d[\\s]+\\d{11}[-\\s]?\\d[\\s]+\\d{11}[-\\s]?\\d")
            .findAll(compacto).forEach { boletosEncontrados.add(it.value.trim()) }

        // 3. Valores monetários (R$)
        val valoresEncontrados = Regex("R\\$\\s?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|R\\$\\s?\\d+,\\d{2}")
            .findAll(compacto)
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .take(8)
            .toList()

        // 4. Datas (dd/MM/yyyy ou dd/MM/yy)
        val datasEncontradas = Regex("\\b\\d{2}/\\d{2}/\\d{2,4}\\b")
            .findAll(compacto)
            .map { it.value }
            .distinct()
            .take(6)
            .toList()

        // 5. CPF / CNPJ
        val docsEncontrados = Regex("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b|\\b\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}\\b")
            .findAll(compacto)
            .map { it.value }
            .distinct()
            .take(6)
            .toList()

        // 6. Classificação Inteligente Automática
        val categoria = when {
            boletosEncontrados.isNotEmpty() || norm.contains("boleto") || norm.contains("linha digitavel") || norm.contains("codigo de barras") ->
                "Boleto / Fatura"
            pixEncontrados.isNotEmpty() || norm.contains("comprovante") || norm.contains("transferencia") || norm.contains("pix") || norm.contains("autenticacao") ->
                "Comprovante / PIX"
            norm.contains("nota fiscal") || norm.contains("danfe") || norm.contains("nf-e") || norm.contains("cupom fiscal") || norm.contains("recibo") ->
                "Nota Fiscal / Recibo"
            nomeArquivo.endsWith(".pdf", ignoreCase = true) || norm.contains("contrato") || norm.contains("clausula") || norm.contains("declaracao") || norm.contains("certidao") ->
                "Documento / Contrato"
            nomeArquivo.contains("Screenshot", ignoreCase = true) || norm.contains("whatsapp") || norm.contains("mensagem") ->
                "Print / Conversa"
            compacto.length >= 15 -> "Texto / Anotação"
            else -> "Mídia Visual"
        }

        return ResultadoAnaliseIA(
            textoCompleto = compacto.trim(),
            categoria = categoria,
            codigosPix = pixEncontrados.distinct().take(4),
            boletos = boletosEncontrados.distinct().take(3),
            valores = valoresEncontrados,
            datas = datasEncontradas,
            documentos = docsEncontrados
        )
    }
}

fun copiarTextoParaAreaTransferencia(context: Context, rotulo: String, conteudo: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(rotulo, conteudo))
        Toast.makeText(context, "$rotulo copiado!", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {}
}

@Composable
fun DialogAnaliseIA(
    context: Context,
    nomeArquivo: String,
    analisando: Boolean,
    resultado: ResultadoAnaliseIA?,
    onReanalisar: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDarker,
            border = BorderStroke(1.5.dp, Gold),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Gold, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("NEXUS IA • LEITURA OFFLINE", color = Gold, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            Text(nomeArquivo, color = TextGrey, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextGrey)
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (analisando) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Gold)
                        Spacer(Modifier.height(14.dp))
                        Text("Lendo texto, valores e códigos PIX...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Processamento 100% local e privado", color = TextGrey, fontSize = 11.sp)
                    }
                } else if (resultado == null || resultado.textoCompleto.isBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.TextSnippet, null, tint = TextGrey, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Nenhum texto legível detectado neste arquivo.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("A IA lê fotos com texto, comprovantes, boletos, prints e PDFs.", color = TextGrey, fontSize = 11.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Gold.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CLASSIFICAÇÃO IA", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(resultado.categoria.uppercase(Locale.ROOT), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }

                    if (resultado.codigosPix.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("PIX DETECTADO", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        resultado.codigosPix.forEach { pix ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(SurfaceDark, RoundedCornerShape(10.dp))
                                    .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.QrCode2, null, tint = Gold, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    pix,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { copiarTextoParaAreaTransferencia(context, "Código PIX", pix) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("COPIAR", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    if (resultado.boletos.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("BOLETO / CÓDIGO DE BARRAS", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        resultado.boletos.forEach { bol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(SurfaceDark, RoundedCornerShape(10.dp))
                                    .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    bol,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val soDigitos = bol.replace(Regex("[^0-9]"), "")
                                        copiarTextoParaAreaTransferencia(context, "Código do Boleto", soDigitos)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("COPIAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    if (resultado.valores.isNotEmpty() || resultado.datas.isNotEmpty() || resultado.documentos.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceDark, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (resultado.valores.isNotEmpty()) {
                                Text("💰 Valores: ${resultado.valores.joinToString("  •  ")}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            if (resultado.datas.isNotEmpty()) {
                                Text("📅 Datas: ${resultado.datas.joinToString("  •  ")}", color = Color.White, fontSize = 12.sp)
                            }
                            if (resultado.documentos.isNotEmpty()) {
                                Text("🪪 CPF/CNPJ: ${resultado.documentos.joinToString("  •  ")}", color = TextGrey, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TEXTO EXTRAÍDO (INDEXADO NA BUSCA)", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = { copiarTextoParaAreaTransferencia(context, "Texto extraído", resultado.textoCompleto) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, tint = Gold, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("COPIAR TUDO", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 210.dp)
                            .background(BgBlack, RoundedCornerShape(10.dp))
                            .border(1.dp, SurfaceDark, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(resultado.textoCompleto, color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onReanalisar,
                        enabled = !analisando,
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text("RELER", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.weight(1.2f).height(44.dp)
                    ) {
                        Text("FECHAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
