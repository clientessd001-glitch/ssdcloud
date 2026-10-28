package com.seu.nexuscloud

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tabela_pastas")
data class PastaNuvem(@PrimaryKey(autoGenerate = true) val id: Int = 0, val nomePasta: String)

@Entity(
    tableName = "tabela_arquivos",
    foreignKeys = [ForeignKey(entity = PastaNuvem::class, parentColumns = ["id"], childColumns = ["pastaId"], onDelete = ForeignKey.SET_NULL)]
)
data class ArquivoNuvem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: Int, val nomeArquivo: String, val tamanhoBytes: Long, val dataUpload: Long,
    val caminhoMiniatura: String?, val pastaId: Int? = null
)

@Dao
interface NexusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun criarPasta(pasta: PastaNuvem)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun salvarArquivo(arquivo: ArquivoNuvem)
    @Query("SELECT * FROM tabela_arquivos ORDER BY dataUpload DESC") fun buscarTodos(): Flow<List<ArquivoNuvem>>
    @Query("SELECT * FROM tabela_pastas ORDER BY nomePasta ASC") fun buscarPastas(): Flow<List<PastaNuvem>>
    @Query("SELECT SUM(tamanhoBytes) FROM tabela_arquivos") fun calcularUsoTotal(): Flow<Long?>
    @Query("UPDATE tabela_arquivos SET pastaId = :pastaId WHERE id = :arqId") suspend fun moverArquivo(arqId: Int, pastaId: Int?)
    @Query("DELETE FROM tabela_arquivos WHERE id = :arqId") suspend fun apagarArquivo(arqId: Int)
}

@Database(entities = [ArquivoNuvem::class, PastaNuvem::class], version = 1, exportSchema = false)
abstract class NexusDB : RoomDatabase() {
    abstract fun dao(): NexusDao
    companion object {
        @Volatile private var INSTANCE: NexusDB? = null
        fun get(ctx: Context): NexusDB = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(ctx, NexusDB::class.java, "nexus_db").build().also { INSTANCE = it }
        }
    }
}
