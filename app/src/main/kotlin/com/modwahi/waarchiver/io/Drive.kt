package com.modwahi.waarchiver.io

import com.modwahi.waarchiver.core.UploadError
import com.modwahi.waarchiver.core.Uploader
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * جسم طلب يرفع ملفاً ويبلّغ عن البايتات المرسلة. عند إعادة المحاولة يطرح ما أبلغ عنه في المحاولة السابقة
 * حتى يبقى العدّاد الكلي دقيقاً.
 */
class CountingFileBody(private val file: File, private val type: okhttp3.MediaType, private val onBytes: ((Long) -> Unit)?) : RequestBody() {
    private var sentThisAttempt = 0L
    override fun contentType() = type
    override fun contentLength() = file.length()
    override fun writeTo(sink: okio.BufferedSink) {
        if (sentThisAttempt > 0) { onBytes?.invoke(-sentThisAttempt); sentThisAttempt = 0 }
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sentThisAttempt += n
                onBytes?.invoke(n.toLong())
            }
        }
    }
}

/**
 * Google Drive REST v3 بصلاحية drive.file فقط: التطبيق يرى ويكتب فقط الملفات التي أنشأها هو.
 * العمليات المستخدمة: بحث، قراءة، إنشاء، وتحديث محتوى ملف موجود. لا يوجد أي طلب DELETE ولا نقل للمهملات.
 *
 * tokenProvider(forceRefresh) يرجع access token صالحاً (من Google Identity AuthorizationClient).
 */
class DriveClient(private val tokenProvider: (Boolean) -> String) {
    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        fun q(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    @Volatile private var token: String? = null

    /** ينفّذ الطلب مع إعادة المحاولة (5xx، 429، 403 rate limit، انقطاع الشبكة) وتجديد التوكن عند 401. */
    private fun <T> call(build: (Request.Builder) -> Request.Builder, handle: (Response) -> T): T {
        var attempt = 0
        var refreshed = false
        while (true) {
            val t = token ?: tokenProvider(false).also { token = it }
            val req = build(Request.Builder()).header("Authorization", "Bearer $t").build()
            try {
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> return handle(resp)
                        resp.code == 401 && !refreshed -> {
                            refreshed = true
                            token = tokenProvider(true)
                        }
                        (resp.code == 429 || resp.code >= 500 || (resp.code == 403 && resp.peekBody(4096).string()
                            .contains("rateLimitExceeded", ignoreCase = true))) && attempt < 6 -> {
                            Thread.sleep((1000L shl attempt).coerceAtMost(32_000))
                            attempt++
                        }
                        else -> throw UploadError(describe(resp))
                    }
                }
            } catch (e: IOException) {
                if (attempt >= 6) throw UploadError("انقطع الاتصال بـ Google Drive: ${e.message}", e)
                Thread.sleep((1000L shl attempt).coerceAtMost(32_000))
                attempt++
            }
        }
    }

    private fun describe(resp: Response): String {
        val body = runCatching { resp.body.string() }.getOrDefault("")
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))
        return "Google Drive رفض الطلب (${resp.code}): $msg"
    }

    data class DFile(val id: String, val name: String, val mime: String, val size: Long, val trashed: Boolean)

    private fun parse(o: JSONObject) = DFile(o.getString("id"), o.optString("name"), o.optString("mimeType"),
        o.optString("size", "0").toLongOrNull() ?: 0, o.optBoolean("trashed"))

    fun get(id: String): DFile? = try {
        call({ it.url("$API/files/$id?fields=id,name,mimeType,size,trashed&supportsAllDrives=true") }) {
            parse(JSONObject(it.body.string()))
        }
    } catch (e: UploadError) {
        if (e.message?.contains("(404)") == true) null else throw e
    }

    fun find(parentId: String, name: String, folder: Boolean? = null): DFile? {
        val cond = buildString {
            append("'${q(parentId)}' in parents and name = '${q(name)}' and trashed = false")
            if (folder == true) append(" and mimeType = '$FOLDER_MIME'")
            if (folder == false) append(" and mimeType != '$FOLDER_MIME'")
        }
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", cond)
            .addQueryParameter("fields", "files(id,name,mimeType,size,trashed)")
            .addQueryParameter("pageSize", "10")
            .addQueryParameter("spaces", "drive")
            .build()
        return call({ it.url(url) }) { r ->
            val a: JSONArray = JSONObject(r.body.string()).optJSONArray("files") ?: JSONArray()
            (0 until a.length()).map { parse(a.getJSONObject(it)) }.firstOrNull()
        }
    }

    /** كل محتويات مجلد (غير المحذوفة) — طلب واحد لكل 1000 عنصر بدل بحث لكل ملف. */
    fun listChildren(parentId: String): List<DFile> {
        val out = ArrayList<DFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${q(parentId)}' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,trashed)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            pageToken = call({ it.url(url) }) { r ->
                val o = JSONObject(r.body.string())
                val a = o.optJSONArray("files") ?: JSONArray()
                for (k in 0 until a.length()) out.add(parse(a.getJSONObject(k)))
                o.optString("nextPageToken").takeIf { it.isNotEmpty() }
            }
        } while (pageToken != null)
        return out
    }

    /** مجلد أرشيف وجده التطبيق في الدرايف */
    data class ArchiveRoot(val id: String, val name: String, val createdMs: Long, val lastRunMs: Long, val chats: Int)

    private fun query(q: String, fields: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q).addQueryParameter("fields", "nextPageToken,files($fields)")
                .addQueryParameter("pageSize", "200").addQueryParameter("spaces", "drive")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()
            pageToken = call({ it.url(url) }) { r ->
                val o = JSONObject(r.body.string())
                val a = o.optJSONArray("files") ?: JSONArray()
                for (k in 0 until a.length()) out.add(a.getJSONObject(k))
                o.optString("nextPageToken").takeIf { it.isNotEmpty() }
            }
        } while (pageToken != null)
        return out
    }

    private fun isoMs(s: String?): Long = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

    /**
     * يبحث عن مجلدات الأرشيف التي يستطيع التطبيق الوصول إليها (أنشأها هو، حتى لو نُقلت أو غُيّر اسمها):
     * المجلد الذي فيه «_سجل التشغيل»، أو المجلد باسم التطبيق الافتراضي. الأحدث تشغيلاً أولاً.
     */
    fun archiveRoots(defaultName: String, logDirName: String): List<ArchiveRoot> {
        val folder = "mimeType = '$FOLDER_MIME' and trashed = false"
        val lastRun = HashMap<String, Long>()
        for (log in query("name = '${q(logDirName)}' and $folder", "id,parents,modifiedTime")) {
            val p = log.optJSONArray("parents")?.optString(0) ?: continue
            lastRun[p] = maxOf(lastRun[p] ?: 0L, isoMs(log.optString("modifiedTime")))
        }
        for (f in query("name = '${q(defaultName)}' and $folder", "id")) lastRun.putIfAbsent(f.getString("id"), 0L)
        return lastRun.mapNotNull { (id, run) ->
            runCatching {
                val meta = call({ it.url("$API/files/$id?fields=id,name,createdTime,trashed") }) { JSONObject(it.body.string()) }
                if (meta.optBoolean("trashed")) return@runCatching null
                val chats = listChildren(id).count { it.mime == FOLDER_MIME && it.name != logDirName }
                ArchiveRoot(id, meta.optString("name"), isoMs(meta.optString("createdTime")), run, chats)
            }.getOrNull()
        }.sortedWith(compareByDescending<ArchiveRoot> { it.lastRunMs }.thenBy { it.createdMs })
    }

    fun createFolder(name: String, parentId: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        return call({ it.url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON)) }) {
            JSONObject(it.body.string()).getString("id")
        }
    }

    fun download(id: String): ByteArray = call({ it.url("$API/files/$id?alt=media") }) { it.body.bytes() }

    /** إنشاء ملف جديد (رفع resumable يتحمل الملفات الكبيرة). */
    fun create(parentId: String, name: String, file: File, mime: String, onBytes: ((Long) -> Unit)? = null): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        val session = call({
            it.url("$UPLOAD/files?uploadType=resumable&fields=id")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(meta.toString().toRequestBody(JSON))
        }) { it.header("Location") ?: throw UploadError("Google Drive لم يرجع رابط الرفع") }
        return call({ it.url(session).put(CountingFileBody(file, mime.toMediaType(), onBytes)) }) {
            JSONObject(it.body.string()).getString("id")
        }
    }

    /** تحديث محتوى ملف موجود (الرسائل.json/txt والملخص) — بدون حذف. */
    fun update(id: String, file: File, mime: String, onBytes: ((Long) -> Unit)? = null) {
        val body: RequestBody = CountingFileBody(file, mime.toMediaType(), onBytes)
        call({ it.url("$UPLOAD/files/$id?uploadType=media&fields=id").patch(body) }) { }
    }
}

/**
 * Uploader فوق Drive: كل المسارات نسبية لمجلد التطبيق (folder ID محفوظ).
 * المجلد يمكن نقله يدوياً لأي مكان في الدرايف: نستخدم الـ ID لا المسار.
 *
 * للسرعة: نقرأ محتوى كل مجلد مرة واحدة (بدل بحث لكل ملف)، ونرفع حتى 3 ملفات بالتوازي.
 */
class DriveUploader(
    private val drive: DriveClient,
    private val rootId: String,
) : Uploader {
    private val dirIds = HashMap<String, String>()               // مسار نسبي ← ID
    private val listings = HashMap<String, MutableMap<String, DriveClient.DFile>>() // ID ← (اسم ← ملف)
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

    @Synchronized
    private fun children(dirId: String): MutableMap<String, DriveClient.DFile> = listings.getOrPut(dirId) {
        drive.listChildren(dirId).associateByTo(java.util.concurrent.ConcurrentHashMap()) { it.name }
    }

    @Synchronized
    private fun dirId(relDir: String, create: Boolean): String? {
        var cur = rootId
        var path = ""
        for (part in relDir.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path/$part"
            cur = dirIds[path] ?: run {
                val kids = children(cur)
                val found = kids[part]?.takeIf { it.mime == DriveClient.FOLDER_MIME }?.id
                    ?: if (create) drive.createFolder(part, cur).also { id ->
                        kids[part] = DriveClient.DFile(id, part, DriveClient.FOLDER_MIME, 0, false)
                        listings[id] = java.util.concurrent.ConcurrentHashMap()
                    } else return null
                found.also { dirIds[path] = it }
            }
        }
        return cur
    }

    /** محتوى مجلد في الدرايف (لمزامنة نسخة القراءة) */
    fun list(relDir: String): List<Entry> {
        val dir = dirId(relDir, create = false) ?: return emptyList()
        return children(dir).values.map { Entry(it.name, it.mime == DriveClient.FOLDER_MIME, it.size) }
    }

    override fun readFile(relDir: String, name: String): ByteArray? {
        val dir = dirId(relDir, create = false) ?: return null
        val f = children(dir)[name]?.takeIf { it.mime != DriveClient.FOLDER_MIME } ?: return null
        return drive.download(f.id)
    }

    override fun uploadDir(localDir: File, relDir: String) = uploadDir(localDir, relDir) { }

    override fun uploadDir(localDir: File, relDir: String, onBytes: (Long) -> Unit) {
        val report: (Long) -> Unit = { synchronized(this) { onBytes(it) } }
        val files = localDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val jobs = files.map { src ->
            val relParent = src.parentFile!!.relativeTo(localDir).path
            val dir = dirId(listOf(relDir, relParent).filter { it.isNotEmpty() }.joinToString("/"), create = true)!!
            val kids = children(dir)
            pool.submit {
                val mime = mimeOf(src.name)
                val existing = kids[src.name]
                when {
                    existing == null -> {
                        val id = drive.create(dir, src.name, src, mime, report)
                        kids[src.name] = DriveClient.DFile(id, src.name, mime, src.length(), false)
                    }
                    // الرسائل والملخص تتغير: نحدّث المحتوى. الوسائط بنفس الاسم والحجم موجودة: لا داعي لرفعها
                    src.name.endsWith(".json") || src.name.endsWith(".txt") || existing.size != src.length() ->
                        drive.update(existing.id, src, mime, report)
                    else -> report(src.length()) // موجود مسبقاً: لا حاجة لرفعه
                }
            }
        }
        // ننتظر الكل؛ أول خطأ يُرمى كما هو (فلا تتقدم حالة المحادثة)
        var error: Throwable? = null
        for (j in jobs) {
            try { j.get() } catch (e: java.util.concurrent.ExecutionException) { if (error == null) error = e.cause }
        }
        error?.let { throw if (it is UploadError) it else UploadError(it.message ?: it.javaClass.simpleName, it) }
    }

    fun close() = pool.shutdown()

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "txt" -> "text/plain"
        else -> android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
