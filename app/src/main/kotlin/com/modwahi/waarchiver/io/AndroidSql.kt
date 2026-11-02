package com.modwahi.waarchiver.io

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import com.modwahi.waarchiver.core.Row
import com.modwahi.waarchiver.core.SqlDb
import com.modwahi.waarchiver.core.SqlOpener
import java.io.File

/** SqlOpener فوق android.database.sqlite — يعمل فقط على النسخة المفكوكة المؤقتة في cache التطبيق. */
object AndroidSql : SqlOpener {
    class ADb(val db: SQLiteDatabase) : SqlDb {
        override fun query(sql: String, args: List<Any?>): List<Row> {
            // نربط القيم بأنواعها الحقيقية (rawQuery العادي يربط كل شيء كنص)
            val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, q ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> q.bindNull(i + 1)
                        is Long -> q.bindLong(i + 1, a)
                        is Int -> q.bindLong(i + 1, a.toLong())
                        is Double -> q.bindDouble(i + 1, a)
                        is ByteArray -> q.bindBlob(i + 1, a)
                        else -> q.bindString(i + 1, a.toString())
                    }
                }
                SQLiteCursor(driver, editTable, q)
            }
            db.rawQueryWithFactory(factory, sql, null, "").use { c ->
                val names = c.columnNames
                val out = ArrayList<Row>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    val map = HashMap<String, Any?>(names.size * 2)
                    val list = ArrayList<Any?>(names.size)
                    for (i in names.indices) {
                        val v: Any? = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> c.getString(i)
                        }
                        map[names[i]] = v
                        list.add(v)
                    }
                    out.add(Row(map, list))
                }
                return out
            }
        }

        override fun close() = db.close()
    }

    override fun openReadWrite(file: File): SqlDb {
        val db = SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        // النسخة المؤقتة فقط: نحولها لوضع journal عادي حتى تُفتح للقراءة فقط بدون ملفات wal/shm
        runCatching { db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } }
        return ADb(db)
    }

    override fun openReadOnly(file: File): SqlDb = try {
        ADb(SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS))
    } catch (e: Exception) {
        if (!file.exists()) throw e
        openReadWrite(file) // نسخة مؤقتة على أي حال؛ لا ننفّذ عليها إلا SELECT
    }

    override fun exec(db: SqlDb, sql: String) = (db as ADb).db.execSQL(sql)
}
