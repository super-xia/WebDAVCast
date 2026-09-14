package com.webdavcast.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 地址解析:请求 B.com → 解析出所有 `http://host:port/path[,名称]` 条目。
 * 支持: 纯文本多行 / HTML <a href> 链接 / 一行多个 混排。
 * 方法: 先提取 <a href="URL">名称</a> 为 "URL,名称", 再锚点法全局找所有 host:port。
 */
class AddressResolver(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): List<ServerEntry> = withContext(Dispatchers.IO) {
        val url = Prefs.getBcom(context)
        if (url.isBlank()) return@withContext emptyList()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "WebdavCast/1.0")
            .build()

        val text = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        // 1. <a href="URL">名称</a> → "URL,名称"
        var clean = text.replace(
            Regex("""<a\s+href=["']([^"']+)["'][^>]*>\s*([^<]{0,30})?\s*</a>""", RegexOption.IGNORE_CASE)
        ) { m ->
            val u = m.groupValues[1].trim()
            val n = m.groupValues[2].trim()
            if (n.isEmpty()) u else "$u,$n"
        }
        // 2. 其余 HTML 标签转分隔
        clean = clean
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(p|div|li|tr|span)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")

        // 3. 锚点法: 找所有 host:port(/path) 锚点, 名称取锚点后到下一个锚点前的片段
        val anchors = Regex("""(?:https?://)?([A-Za-z0-9.\-_]+)[:：](\d{1,5})((?:/[^\s,，;]*)?)""")
            .findAll(clean).toList()

        val out = mutableListOf<ServerEntry>()
        for (i in anchors.indices) {
            val a = anchors[i]
            val host = a.groupValues[1].trim()
            val port = a.groupValues[2].toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            val path = a.groupValues[3].trim().removeSuffix("/")
            // 名称 = 锚点后到下一个锚点前的文本
            val end = if (i + 1 < anchors.size) anchors[i + 1].range.first else clean.length
            val tail = clean.substring(a.range.last + 1, end)
            var name = ""
            val m = Regex("""[,，;]\s*([^\n,，;]{1,20})""").find(tail)
            if (m != null) name = m.groupValues[1].trim()
            else if (tail.isNotBlank()) name = tail.trim().split(Regex("\\s+"))[0].take(20)
            out += ServerEntry(host, port, name, path)
        }
        // 不去重: B.com 页面是权威列表, 给几条显示几条(同 host:port 不同名称的条目都要保留)
        out
    }

    companion object {
        /** ServerEntry 列表序列化为缓存字符串(每行: host|port|name|basePath)。 */
        fun encode(list: List<ServerEntry>): String =
            list.joinToString("\n") { "${it.host}|${it.port}|${it.name}|${it.basePath}" }

        /** 从缓存字符串还原列表。 */
        fun decode(s: String): List<ServerEntry> {
            if (s.isBlank()) return emptyList()
            val out = mutableListOf<ServerEntry>()
            for (line in s.lineSequence()) {
                val p = line.split("|", limit = 4)
                if (p.size < 2) continue
                val port = p[1].toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                out += ServerEntry(p[0], port, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
            }
            return out
        }
    }
}
