package com.local.ondevicedocumentassistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.local.ondevicedocumentassistant.SecurePreferences
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        DocumentEntity::class,
        DocumentChunkEntity::class,
        DocumentFieldEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun documentFieldDao(): DocumentFieldDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            val passphrase = SecurePreferences.databasePassphrase(context)
            val factory = SupportFactory(passphrase, null, true)
            val database = instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "documents.db",
            ).openHelperFactory(factory).addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(chunk_text)")
                    createFtsTriggers(db)
                }
            }).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build().also { created ->
                created.openHelper.writableDatabase
                instance = created
            }
            passphrase.fill(0)
            database
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN country TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_fields (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        document_id INTEGER NOT NULL,
                        field_name TEXT NOT NULL,
                        field_value TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_fields_document_id ON document_fields(document_id)",
                )
            }

        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(chunk_text)")
                db.execSQL("INSERT INTO chunks_fts(rowid, chunk_text) SELECT id, chunk_text FROM document_chunks")
                createFtsTriggers(db)
            }
        }

        private fun createFtsTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS chunks_fts_insert AFTER INSERT ON document_chunks BEGIN INSERT INTO chunks_fts(rowid, chunk_text) VALUES (new.id, new.chunk_text); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS chunks_fts_update AFTER UPDATE OF chunk_text ON document_chunks BEGIN UPDATE chunks_fts SET chunk_text = new.chunk_text WHERE rowid = new.id; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS chunks_fts_delete AFTER DELETE ON document_chunks BEGIN DELETE FROM chunks_fts WHERE rowid = old.id; END",
            )
        }
    }
}