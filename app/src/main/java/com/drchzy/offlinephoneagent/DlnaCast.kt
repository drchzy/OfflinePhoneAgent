package com.drchzy.offlinephoneagent

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** DLNA MediaRenderer / AVTransport；仅投送无需额外认证的可直连媒体 URL。 */
data class DlnaDevice(val name: String, val controlUrl: String, val serviceType: String)

object DlnaCast {
    fun discover(context: Context): List<DlnaDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("offlinephoneagent-dlna").apply { setReferenceCounted(false) }
        val discovered = linkedSetOf<String>()
        runCatching { lock.acquire() }
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 650
                val payload = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size,
                    InetSocketAddress("239.255.255.250", 1900)))
                val until = System.currentTimeMillis() + 2800
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                    val data = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val location = data.lineSequence().firstOrNull {
                        it.startsWith("location:", ignoreCase = true)
                    }?.substringAfter(':')?.trim()
                    if (!location.isNullOrBlank() && location.startsWith("http", true)) {
                        discovered.add(location)
                    }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        return discovered.mapNotNull { location ->
            runCatching { readDevice(location) }.getOrNull()
        }.distinctBy { it.controlUrl }
    }

    private fun readDevice(location: String): DlnaDevice? {
        val url = URL(location)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
        }
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            conn.inputStream.use { factory.newDocumentBuilder().parse(it) }
        } finally { conn.disconnect() }
        val friendly = doc.getElementsByTagName("friendlyName").item(0)?.textContent
            ?.trim()?.takeIf { it.isNotBlank() } ?: url.host
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val node = services.item(i)
            val children = node.childNodes
            var type = ""
            var path = ""
            for (j in 0 until children.length) {
                val e = children.item(j)
                if (e.nodeName == "serviceType") type = e.textContent.trim()
                if (e.nodeName == "controlURL") path = e.textContent.trim()
            }
            if (type.contains("AVTransport") && path.isNotBlank()) {
                return DlnaDevice(friendly, URL(url, path).toString(), type)
            }
        }
        return null
    }

    fun play(device: DlnaDevice, url: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "当前地址不是可投屏的 HTTP 媒体 URL"
        }
        val escaped = url.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
        soap(device, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>$escaped</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    private fun soap(device: DlnaDevice, action: String, arguments: String) {
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${device.serviceType}\">$arguments</u:$action>" +
            "</s:Body></s:Envelope>").toByteArray(StandardCharsets.UTF_8)
        val conn = (URL(device.controlUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"" + device.serviceType + "#" + action + "\"")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val message = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("电视拒绝投屏（HTTP $status）：" + message.take(180))
            }
        } finally { conn.disconnect() }
    }
}
