package com.webdavcast.app

/** 从 B.com 解析出的一台 WebDAV 服务器。basePath 如 "/dav"。 */
data class ServerEntry(
    val host: String,
    val port: Int,
    val name: String,
    val basePath: String = "",
) {
    val display: String
        get() {
            val addr = "$host:$port$basePath"
            return if (name.isBlank()) addr else "$name ($addr)"
        }
}
