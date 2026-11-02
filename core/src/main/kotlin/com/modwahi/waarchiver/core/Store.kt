package com.modwahi.waarchiver.core

import java.io.File
import java.io.IOException
import java.time.ZoneId

const val JSON_NAME = "الرسائل.json"
const val TXT_NAME = "الرسائل.txt"

fun writeAtomic(path: File, data: ByteArray) {
    path.parentFile?.mkdirs()
    val tmp = File(path.parentFile, path.name + ".tmp")
    tmp.writeBytes(data)
    if (!tmp.renameTo(path)) {
        path.delete()
        if (!tmp.renameTo(path)) throw IOException("تعذّر كتابة ${path.name}")
    }
}

/**
 * سجلات يوم واحد لمحادثة واحدة: <المحادثة>/<YYYY-MM-DD>/الرسائل.json و الرسائل.txt — منقول من store.py.
 * الأساس هو JSON، والـ txt يُولَّد منه دائماً. الدمج دون تكرار حسب id.
 */
class DayFile(records: List<JObj> = emptyList()) {
    val records: MutableList<JObj> = records.toMutableList()
    private val ids: MutableSet<Any?> = records.map { it["id"] }.toMutableSet()

    companion object {
        fun fromJsonBytes(data: ByteArray?): DayFile {
            if (data == null || data.isEmpty()) return DayFile()
            var text = String(data, Charsets.UTF_8)
            if (text.startsWith("﻿")) text = text.substring(1)
            if (text.isBlank()) return DayFile()
            val recs = Json.parse(text) as? List<*> ?: throw IllegalArgumentException("ملف الرسائل.json ليس قائمة")
            @Suppress("UNCHECKED_CAST")
            return DayFile(recs.map { it as JObj })
        }
    }

    fun has(recId: String): Boolean = recId in ids

    fun add(recs: List<JObj>): Int {
        var added = 0
        for (r in recs) {
            if (r["id"] in ids) continue
            ids.add(r["id"])
            records.add(r)
            added++
        }
        return added
    }

    fun update(recId: String, fields: Map<String, Any?>): Boolean {
        for (r in records) {
            if (r["id"] == recId) {
                r.putAll(fields)
                return true
            }
        }
        return false
    }

    fun sorted(): List<JObj> = records.sortedWith(
        compareBy<JObj>({ it["timestamp"].asLong() }, { it["row_id"].asLong() }, { pyStr(it["id"]) })
    )

    fun jsonBytes(): ByteArray = (Json.dumps(sorted()) + "\n").toByteArray(Charsets.UTF_8)

    /** BOM + CRLF حتى يفتحه Notepad في ويندوز بالعربي صحيحاً */
    fun txtBytes(zone: ZoneId): ByteArray =
        ("﻿" + sorted().joinToString("\r\n") { Naming.formatTxtLine(it, zone) } + "\r\n").toByteArray(Charsets.UTF_8)

    fun write(dayDir: File, zone: ZoneId) {
        writeAtomic(File(dayDir, JSON_NAME), jsonBytes())
        writeAtomic(File(dayDir, TXT_NAME), txtBytes(zone))
    }
}

/** state.json: آخر _id و timestamp لكل محادثة، والوسائط المعلّقة، وآخر نسخة عولجت — منقول من state.py */
class State(private val path: File) {
    val data: JObj

    init {
        var d = JObj()
        if (path.exists()) {
            var text = path.readText(Charsets.UTF_8)
            if (text.startsWith("﻿")) text = text.substring(1)
            if (text.isNotBlank()) {
                @Suppress("UNCHECKED_CAST")
                d = Json.parse(text) as JObj
            }
        }
        data = JObj()
        data["chats"] = JObj()
        data.putAll(d)
    }

    @Suppress("UNCHECKED_CAST")
    private val chats: JObj get() = data["chats"] as JObj

    @Suppress("UNCHECKED_CAST")
    fun chat(jid: String): JObj {
        val c = chats.getOrPut(jid) { JObj() } as JObj
        c.putIfAbsent("last_row_id", 0L)
        c.putIfAbsent("last_timestamp", 0L)
        c.putIfAbsent("pending_media", ArrayList<Any?>())
        return c
    }

    fun hasChat(jid: String): Boolean = truthy((chats[jid] as? Map<*, *>)?.get("last_timestamp"))

    var lastBackupMtime: Double
        get() = data["last_backup_mtime"].asDouble()
        set(v) { data["last_backup_mtime"] = v }

    fun save() = writeAtomic(path, (Json.dumps(data) + "\n").toByteArray(Charsets.UTF_8))
}
