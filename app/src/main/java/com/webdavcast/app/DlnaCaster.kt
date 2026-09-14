package com.webdavcast.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** DLNA 投屏:SSDP 发现电视 + AVTransport SOAP 控制。自研,不依赖第三方 UPnP SDK。 */
class DlnaCaster {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    companion object {
        const val SSDP_ADDR = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    }

    /** SSDP 搜索 MediaRenderer(电视)。 */
    suspend fun discover(timeoutMs: Long = 5000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<DlnaDevice>()
            val socket = try {
                DatagramSocket().apply { soTimeout = 1200 }
            } catch (e: Exception) {
                return@withContext out
            }
            socket.broadcast = true
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() - lastSend > 1100) {
                        val msg = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 3\r\n" +
                            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                        socket.send(
                            DatagramPacket(
                                msg.toByteArray(), msg.length,
                                InetAddress.getByName(SSDP_ADDR), SSDP_PORT
                            )
                        )
                        lastSend = System.currentTimeMillis()
                    }
                    val buf = ByteArray(8192)
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    val resp = String(p.data, 0, p.length)
                    val loc = resp.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    fetchDevice(loc)?.let { d ->
                        if (out.none { it.udn == d.udn }) out += d
                    }
                }
            } catch (e: Exception) {
            } finally {
                socket.close()
            }
            out
        }

    private fun fetchDevice(location: String): DlnaDevice? {
        try {
            val req = Request.Builder()
                .url(location)
                .header("User-Agent", "WebDAVCast/1.0")
                .build()
            val xml = http.newCall(req).execute().use { it.body?.string() } ?: return null
            val renderer = Regex("""<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>""")
                .containsMatchIn(xml)
            if (!renderer) return null
            val udn = Regex("""<UDN>uuid:([^<]+)</UDN>""")
                .find(xml)?.groupValues?.get(1)?.trim() ?: return null
            val name = Regex("""<friendlyName>([^<]+)</friendlyName>""")
                .find(xml)?.groupValues?.get(1)?.trim() ?: "电视"
            val avt = Regex(
                """<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>.*?<controlURL>([^<]+)</controlURL>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml)?.groupValues?.get(1)?.trim()
            val controlUrl = avt?.let { resolve(location, it) } ?: return null
            return DlnaDevice(udn, name, location, controlUrl)
        } catch (e: Exception) {
            return null
        }
    }

    private fun resolve(location: String, u: String): String {
        if (u.startsWith("http")) return u
        return if (u.startsWith("/")) {
            "${location.substringBefore("://")}://${location.substringAfter("://").substringBefore('/')}$u"
        } else {
            "${location.substringBeforeLast('/')}/$u"
        }
    }

    /** 让电视拉 WebDAV URL(电视需支持 Basic 认证)。title 为视频文件名(会显示在电视上)。 */
    suspend fun play(
        device: DlnaDevice,
        url: String,
        title: String = "",
        size: Long = 0,
        modified: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        // 标准 UPnP 协议: CurrentURIMetaData 是嵌在 SOAP XML 里的 XML 字符串,
        // 必须整体 XML 转义(< > & -> &lt; &gt; &amp;), 否则电视解析不到标题等元数据
        val didl = buildDidl(url, title, size, modified)
        val escapedDidl = xmlEscape(didl)
        val ok = soap(
            device.controlUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(url)}</CurrentURI><CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>"
        )
        ok && soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    /** 生成完整的 DLNA DIDL-Lite 元数据(标题/创作者/class/日期/大小/res属性等)。 */
    private fun buildDidl(url: String, title: String, size: Long, modified: String): String {
        val safeTitle = xmlEscape(title.ifBlank { "视频" })
        val fileExt = title.substringAfterLast('.', "").lowercase()
        // 根据扩展名推断 MIME 类型(供电视判断解码器)
        val mime = when (fileExt) {
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "webm" -> "video/webm"
            "m4v", "3gp", "3gpp" -> "video/mp4"
            else -> "video/mp4"
        }
        // upnp:class: 视频条目
        val upnpClass = "object.item.videoItem"
        // res 属性: 大小 + 时长占位(时长需要探测, 未探测则不填)
        val sizeAttr = if (size > 0) " size=\"$size\"" else ""
        val dateAttr = if (modified.isNotBlank()) "<dc:date>${xmlEscape(modified)}</dc:date>" else ""
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
<item id="0" parentID="-1" restricted="1">
<dc:title>$safeTitle</dc:title>
<dc:creator>WebDAV</dc:creator>
$dateAttr
<upnp:class>$upnpClass</upnp:class>
<res protocolInfo="http-get:*:$mime:*"$sizeAttr>$url</res>
</item>
</DIDL-Lite>"""
    }

    /** XML 特殊字符转义, 防止文件名里的 & < > 破坏 DIDL 元数据。 */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * 手动投屏:用户填电视 IP(可选端口),App 拉取电视的 UPnP 描述 XML,
     * 解析出 AVTransport control URL, 然后 SetAVTransportURI + Play。
     * 用于 SSDP 发现不到电视时的兜底。
     */
    suspend fun playManual(
        ipPort: String,
        url: String,
        title: String = "",
        size: Long = 0,
        modified: String = "",
    ): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val host = ipPort.trim()
            if (host.isEmpty()) return@withContext false to "请填写电视 IP"
            // 尝试常见 UPnP 描述地址: http://ip:port/rootDesc.xml
            val candidates = if (host.contains(":")) {
                listOf("http://$host/rootDesc.xml", "http://$host/upnp/desc/device/deviceDesc.xml")
            } else {
                listOf(
                    "http://$host:49152/rootDesc.xml",
                    "http://$host:49153/rootDesc.xml",
                    "http://$host:1900/rootDesc.xml",
                    "http://$host/rootDesc.xml"
                )
            }
            for (loc in candidates) {
                try {
                    val device = fetchDevice(loc) ?: continue
                    val ok = play(device, url, title, size, modified)
                    if (ok) return@withContext true to "已在 ${device.friendlyName} 播放"
                } catch (e: Exception) { /* 试下一个 */ }
            }
            false to "连不上电视的 UPnP 服务,请确认 IP/端口正确"
        }

    private fun soap(controlUrl: String, action: String, args: String): Boolean = try {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$AVT">$args</u:$action></s:Body>
</s:Envelope>"""
        val req = Request.Builder()
            .url(controlUrl)
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("SOAPACTION", "\"$AVT#$action\"")
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("User-Agent", "WebDAVCast/1.0")
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}
