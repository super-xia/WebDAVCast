package com.webdavcast.app

import android.content.Context
import androidx.core.content.edit

/** 播放历史条目。 */
data class PlayHistory(
    val url: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,   // epoch ms
    val dirPath: String = "",   // 视频所在目录(相对路径, 如 /cc/), 用于从历史返回时定位到原目录
)

/** 简单键值存储(记住 B.com 地址、WebDAV 账号、上次选择的服务器、播放历史)。 */
object Prefs {
    private const val NAME = "webdavcast_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getBcom(c: Context): String = sp(c).getString("bcom", "") ?: ""
    fun setBcom(c: Context, v: String) = sp(c).edit { putString("bcom", v) }

    fun getUser(c: Context): String = sp(c).getString("user", "") ?: ""
    fun setUser(c: Context, v: String) = sp(c).edit { putString("user", v) }

    fun getPass(c: Context): String = sp(c).getString("pass", "") ?: ""
    fun setPass(c: Context, v: String) = sp(c).edit { putString("pass", v) }

    fun getLast(c: Context): String = sp(c).getString("last", "") ?: ""
    fun setLast(c: Context, v: String) = sp(c).edit { putString("last", v) }

    // 上次连接的服务器信息(host|port|name|basePath), 冷启动恢复用
    fun getLastServer(c: Context): String = sp(c).getString("last_server", "") ?: ""
    fun setLastServer(c: Context, host: String, port: Int, name: String, basePath: String) =
        sp(c).edit { putString("last_server", "$host|$port|$name|$basePath") }

    // 服务器列表缓存: 加载成功后保存, 启动直接读缓存(不自动刷新)
    fun getServerCache(c: Context): String = sp(c).getString("servers_cache", "") ?: ""
    fun setServerCache(c: Context, v: String) = sp(c).edit { putString("servers_cache", v) }

    // ---------- 播放历史 ----------
    // 存储格式: 每行 url|title|positionMs|durationMs|watchedAt
    // title 用 Base64 避免分隔符冲突
    private fun encTitle(t: String) =
        android.util.Base64.encodeToString(t.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    private fun decTitle(b: String): String =
        try { String(android.util.Base64.decode(b, android.util.Base64.NO_WRAP), Charsets.UTF_8) }
        catch (_: Exception) { b }
    private fun encPath(p: String) =
        if (p.isBlank()) "" else android.util.Base64.encodeToString(p.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    private fun decPath(b: String): String =
        if (b.isBlank()) "" else try { String(android.util.Base64.decode(b, android.util.Base64.NO_WRAP), Charsets.UTF_8) }
        catch (_: Exception) { b }

    fun getHistory(c: Context): List<PlayHistory> {
        val raw = sp(c).getString("play_history", "") ?: return emptyList()
        val out = mutableListOf<PlayHistory>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val p = line.split("|")
            if (p.size < 5) continue
            val url = p[0]
            val pos = p[2].toLongOrNull() ?: continue
            val dur = p[3].toLongOrNull() ?: 0L
            val at = p[4].toLongOrNull() ?: 0L
            val dir = if (p.size >= 6) decPath(p[5]) else ""
            out += PlayHistory(url, decTitle(p[1]), pos, dur, at, dir)
        }
        // 最新的在前
        return out.sortedByDescending { it.watchedAt }
    }

    /** 写入一条历史, 最多保留 200 条。
     *  去重规则: 按 URL 路径去重(每个文件独立保留一条进度),
     *  服务器地址变了(a.com→b.com)也算同一条, 只更新进度不新增。 */
    fun saveHistory(c: Context, url: String, title: String, positionMs: Long, durationMs: Long, dirPath: String = "") {
        val k = keyOf(url)
        val list = getHistory(c).filter { keyOf(it.url) != k }.toMutableList()
        list.add(0, PlayHistory(url, title, positionMs, durationMs, System.currentTimeMillis(), dirPath))
        if (list.size > 200) list.removeAt(list.lastIndex)
        sp(c).edit {
            putString("play_history", list.joinToString("\n") {
                "${it.url}|${encTitle(it.title)}|${it.positionMs}|${it.durationMs}|${it.watchedAt}|${encPath(it.dirPath)}"
            })
        }
    }

    /** 历史匹配键: URL 去掉 scheme://host:port 后的路径部分。
     * 服务器地址变了(a.com→b.com)也算同一条, 只更新进度不新增。 */
private fun keyOf(url: String): String {
    val after = url.substringAfter("://", url)
    return after.substringAfter("/", "/")
}

/** 目录键: 统一为 /path/ 格式(根目录为 /)。 */
private fun dirKeyOf(dirPath: String): String {
    return dirPath.trim().trim('/').let { if (it.isEmpty()) "/" else "/$it/" }
}

    /** 按 URL 查历史(没有返回 null)。服务器变了也能按路径匹配到同一条。 */
    fun getHistoryByUrl(c: Context, url: String): PlayHistory? =
        getHistory(c).firstOrNull { keyOf(it.url) == keyOf(url) }

    /** 历史页展示用: 每个目录只保留最新观看的一条(watchedAt 最新的那条)。
     *  dirPath 为空(旧数据)时从 URL 路径推断目录, 避免全归到根目录一组。 */
    fun getHistoryForList(c: Context): List<PlayHistory> {
        val list = getHistory(c)          // 已按 watchedAt 降序
        val seen = mutableSetOf<String>()
        val out = mutableListOf<PlayHistory>()
        for (h in list) {
            val dk = if (h.dirPath.isBlank()) {
                val p = h.url.substringAfter("://", "").substringAfter("/", "")
                dirKeyOf(p.substringBeforeLast("/", ""))
            } else dirKeyOf(h.dirPath)
            if (seen.add(dk)) out.add(h)
        }
        return out
    }

    fun clearHistory(c: Context) = sp(c).edit { remove("play_history") }
}