package com.webdavcast.app

import android.content.Context
import androidx.core.content.edit

/** 简单键值存储(记住 B.com 地址、WebDAV 账号、上次选择的服务器)。 */
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

    // 服务器列表缓存: 加载成功后保存, 启动直接读缓存(不自动刷新)
    fun getServerCache(c: Context): String = sp(c).getString("servers_cache", "") ?: ""
    fun setServerCache(c: Context, v: String) = sp(c).edit { putString("servers_cache", v) }
}
