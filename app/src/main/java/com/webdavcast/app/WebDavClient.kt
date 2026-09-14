package com.webdavcast.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** WebDAV 目录条目。 */
data class DavEntry(
    val name: String,
    val isDir: Boolean,
    val href: String,
    val size: Long = 0,
    val modified: String = "",
)

/** 列目录结果:entries 或 error 二选一。 */
data class DavListResult(
    val entries: List<DavEntry> = emptyList(),
    val error: String? = null,
)

/** 极简 WebDAV 客户端:PROPFIND 列目录 + Basic 认证。 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val auth = Credentials.basic(user, pass)

    suspend fun list(path: String): DavListResult = withContext(Dispatchers.IO) {
        val url = urlOf(path)
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:displayname/>
  </d:prop>
</d:propfind>"""
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .header("Authorization", auth)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                when {
                    !resp.isSuccessful -> DavListResult(error = httpError(resp.code))
                    else -> {
                        val xml = resp.body?.string() ?: return@use DavListResult(error = "响应为空")
                        val entries = parseResponse(xml, path, url)
                        if (entries.isEmpty()) DavListResult(entries = emptyList())
                        else DavListResult(entries = entries)
                    }
                }
            }
        } catch (e: Exception) {
            DavListResult(error = "连接失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun httpError(code: Int): String = when (code) {
        401 -> "认证失败(HTTP 401):用户名或密码错误"
        403 -> "没有权限(HTTP 403)"
        404 -> "路径不存在(HTTP 404):请检查地址里的 /dav 路径是否正确"
        405 -> "该地址不支持 WebDAV(HTTP 405)"
        407 -> "需要代理认证(HTTP 407)"
        else -> "服务器错误(HTTP $code)"
    }

    fun urlOf(path: String): String {
        val b = baseUrl.trimEnd('/')
        val p = path.trimStart('/')
        return if (p.isEmpty()) "$b/" else "$b/$p"
    }

    private fun parseResponse(xml: String, parentPath: String, requestUrl: String): List<DavEntry> {
        if (xml.isBlank()) return emptyList()
        val out = mutableListOf<DavEntry>()
        // 请求 URL 的路径部分(去尾斜杠), 用于识别"目录自身"条目
        // 例: http://host:port/dav/ -> "/dav"; http://host:port/dav/电影/ -> "/dav/电影"
        val selfPath = requestUrl.substringBefore('?').trimEnd('/')
        // 服务器 href 是路径形式(/dav/), 这里也提取纯路径做比较
        val selfPathOnly = if (selfPath.contains("://")) {
            selfPath.substringAfter("://").substringAfter("/", "").let { "/$it" }
        } else selfPath
        // 关键: requestUrl 可能带 URL 编码(%E7%81%AB), 而 href 解析时已解码, 这里必须统一解码再比较
        val selfPathDecoded = try {
            java.net.URLDecoder.decode(selfPathOnly, "UTF-8")
        } catch (e: Exception) {
            selfPathOnly
        }
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var href = ""
            var isDir = false
            var inHref = false
            var size = 0L
            var inSize = false
            var modified = ""
            var inModified = false
            while (event != XmlPullParser.END_DOCUMENT) {
                // Android 的 XmlPullParser 在未启用 namespace 处理时返回带前缀名(如 d:href), 剥离前缀
                val tag = parser.name?.substringAfterLast(':') ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "href" -> inHref = true
                        "collection" -> isDir = true
                        "getcontentlength" -> inSize = true
                        "getlastmodified" -> inModified = true
                    }
                    XmlPullParser.TEXT -> when {
                        inHref -> href += parser.text ?: ""
                        inSize -> size = (parser.text ?: "").toLongOrNull() ?: 0L
                        inModified -> modified = (parser.text ?: "").trim()
                    }
                    XmlPullParser.END_TAG -> when (tag) {
                        "href" -> inHref = false
                        "getcontentlength" -> inSize = false
                        "getlastmodified" -> inModified = false
                        "response" -> {
                            val raw = href.trim()
                            if (raw.isNotBlank()) {
                                val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
                                // 目录自身: href 等于当前请求的目录(可能带/不带尾斜杠), 跳过
                                val decodedTrim = decoded.substringBefore('?').trimEnd('/')
                                val isSelf = decodedTrim == selfPathDecoded ||
                                    decodedTrim == selfPathDecoded.trimEnd('/') ||
                                    decodedTrim.isEmpty() ||
                                    (selfPathDecoded.isEmpty() && decodedTrim.isEmpty())
                                if (!isSelf) {
                                    val name = decoded.trimEnd('/').substringAfterLast('/')
                                    if (name.isNotBlank()) {
                                        out += DavEntry(name, isDir, raw, size, modified)
                                    }
                                }
                            }
                            href = ""; isDir = false; size = 0L; modified = ""
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {}
        return out
    }
}
