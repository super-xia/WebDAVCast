package com.webdavcast.app

/** 从 B.com 解析出的一台 WebDAV 服务器。basePath 如 "/dav"。 */
data class ServerEntry(
    val host: String,
    val port: Int,
    val name: String,
    val basePath: String = "",
    val scheme: String = "http",
) {
    val display: String
        get() {
            val addr = "$scheme://$host:$port$basePath"
            return if (name.isBlank()) addr else "$name ($addr)"
        }
}
