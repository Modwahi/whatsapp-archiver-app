package com.modwahi.waarchiver.work

import com.modwahi.waarchiver.core.JObj
import com.modwahi.waarchiver.core.JSON_NAME
import com.modwahi.waarchiver.core.Json
import com.modwahi.waarchiver.core.LOG_DIR_REMOTE
import com.modwahi.waarchiver.core.Uploader
import com.modwahi.waarchiver.core.writeAtomic
import java.io.File

/**
 * نسخة للقراءة داخل التطبيق: ملفات الرسائل.json فقط (بدون وسائط) لكل محادثة ويوم،
 * بنفس شجرة الوجهة: <المجلد>/<YYYY-MM-DD>/الرسائل.json
 */
class Mirror(val root: File) {
    private val dateRe = Regex("\\d{4}-\\d{2}-\\d{2}")

    data class ChatDir(val folder: String, val days: List<String>, val lastDay: String?)

    fun chats(): List<ChatDir> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name != LOG_DIR_REMOTE }
        .map { d ->
            val days = (d.listFiles() ?: emptyArray()).filter { it.isDirectory && dateRe.matches(it.name) && File(it, JSON_NAME).exists() }
                .map { it.name }.sorted()
            ChatDir(d.name, days, days.lastOrNull())
        }
        .filter { it.days.isNotEmpty() }
        .sortedByDescending { it.lastDay }

    @Suppress("UNCHECKED_CAST")
    fun day(folder: String, date: String): List<JObj> = runCatching {
        var t = File(root, "$folder/$date/$JSON_NAME").readText()
        if (t.startsWith("﻿")) t = t.substring(1)
        (Json.parse(t) as List<*>).map { it as JObj }
    }.getOrDefault(emptyList())

    fun size(folder: String, date: String): Long = File(root, "$folder/$date/$JSON_NAME").length()

    fun bytes(folder: String, date: String): ByteArray? = File(root, "$folder/$date/$JSON_NAME").takeIf { it.isFile }?.readBytes()

    fun save(folder: String, date: String, data: ByteArray) = writeAtomic(File(root, "$folder/$date/$JSON_NAME"), data)

    /** ينسخ ملفات الرسائل.json من مجلد مرفوع إلى نسخة القراءة */
    fun capture(localDir: File, relDir: String) {
        if (relDir == LOG_DIR_REMOTE) return
        localDir.walkTopDown().filter { it.isFile && it.name == JSON_NAME }.forEach { f ->
            val date = f.parentFile!!.name
            if (dateRe.matches(date)) save(relDir, date, f.readBytes())
        }
    }
}

/** يرفع كالمعتاد، ثم يحفظ نسخة القراءة بعد نجاح الرفع فقط */
class MirroringUploader(val inner: Uploader, private val mirror: Mirror) : Uploader {
    /**
     * ملف اليوم: إن كانت نسخة القراءة على التلفون بنفس حجم الملف في الوجهة نقرؤها مباشرة بدل تنزيله.
     * (هي نفس الملف الذي رفعه التطبيق.) عند أي شك نرجع للتنزيل العادي.
     */
    override fun readFile(relDir: String, name: String): ByteArray? {
        val sizer = inner as? com.modwahi.waarchiver.io.RemoteSizes
        val parts = relDir.split('/')
        if (name == JSON_NAME && sizer != null && parts.size == 2) {
            val remote = try { sizer.remoteSize(relDir, name) } catch (e: Exception) { return inner.readFile(relDir, name) }
            if (remote == null) return null
            if (remote > 0 && mirror.size(parts[0], parts[1]) == remote) mirror.bytes(parts[0], parts[1])?.let { return it }
        }
        return inner.readFile(relDir, name)
    }
    override fun uploadDir(localDir: File, relDir: String) = uploadDir(localDir, relDir) { }

    override fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) {
        inner.uploadDir(localDir, relDir, onBytes)
        runCatching { mirror.capture(localDir, relDir) }
    }
}
