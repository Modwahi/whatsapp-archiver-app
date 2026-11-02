package com.modwahi.waarchiver.core

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/** نفس واجهة SqlOpener لكن عبر sqlite-jdbc (للاختبارات على JVM). */
object JdbcOpener : SqlOpener {
    class JdbcDb(val conn: Connection) : SqlDb {
        override fun query(sql: String, args: List<Any?>): List<Row> {
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    val md = rs.metaData
                    val n = md.columnCount
                    val out = ArrayList<Row>()
                    while (rs.next()) {
                        val map = HashMap<String, Any?>()
                        val list = ArrayList<Any?>(n)
                        for (c in 1..n) {
                            val v = when (val o = rs.getObject(c)) {
                                is Int -> o.toLong()
                                is Short -> o.toLong()
                                is Float -> o.toDouble()
                                else -> o
                            }
                            map[md.getColumnLabel(c)] = v
                            list.add(v)
                        }
                        out.add(Row(map, list))
                    }
                    return out
                }
            }
        }

        override fun close() = conn.close()
    }

    override fun openReadWrite(file: File): SqlDb {
        if (!file.exists()) throw java.io.FileNotFoundException(file.path)
        return JdbcDb(SQLiteConfig().createConnection("jdbc:sqlite:" + file.path))
    }

    override fun openReadOnly(file: File): SqlDb {
        if (!file.exists()) throw java.io.FileNotFoundException(file.path)
        val cfg = SQLiteConfig().apply { setReadOnly(true) }
        return JdbcDb(cfg.createConnection("jdbc:sqlite:" + file.path))
    }

    override fun exec(db: SqlDb, sql: String) {
        (db as JdbcDb).conn.createStatement().use { it.execute(sql) }
    }
}
