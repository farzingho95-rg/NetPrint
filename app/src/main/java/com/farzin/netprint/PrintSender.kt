package com.farzin.netprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

/** Sends a job using whichever protocol the printer speaks. */
object PrintSender {
    suspend fun send(p: NetPrinter, data: ByteArray, mime: String, job: String) = withContext(Dispatchers.IO) {
        when (p.protocol) {
            Protocol.IPP -> Ipp.print(p, data, mime, job)
            Protocol.RAW -> raw(p, data)
            Protocol.LPD -> lpd(p, data, job)
        }
    }

    // ---------- RAW / JetDirect (port 9100) ----------
    private fun raw(p: NetPrinter, data: ByteArray) {
        Socket().use { s ->
            s.connect(InetSocketAddress(p.host, p.port), 5000)
            s.getOutputStream().apply { write(data); flush() }
        }
    }

    // ---------- LPD / LPR (RFC 1179, port 515) ----------
    private fun lpd(p: NetPrinter, data: ByteArray, job: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(p.host, p.port), 5000); s.soTimeout = 20000
            val o = s.getOutputStream(); val i = s.getInputStream()
            fun ack() { if (i.read() != 0) throw IOException("LPD rejected the job") }
            val n = "%03d".format(Random.nextInt(1000)); val h = "android"
            val name = job.filter { it.code in 32..126 }.ifBlank { "job" }
            val ctrl = "H$h\nPandroid\nJ$name\nldfA$n$h\nUdfA$n$h\nN$name\n".toByteArray()
            o.write("\u0002${p.queue}\n".toByteArray()); ack()
            o.write("\u0002${ctrl.size} cfA$n$h\n".toByteArray()); ack(); o.write(ctrl); o.write(0); ack()
            o.write("\u0003${data.size} dfA$n$h\n".toByteArray()); ack(); o.write(data); o.write(0); ack()
        }
    }

    // ---------- IPP (port 631) ----------
    object Ipp {
        fun print(p: NetPrinter, data: ByteArray, mime: String, job: String) {
            val paths = listOf(p.path, "ipp/print", "ipp", "printers/${p.queue}", "").distinct()
            var last: Exception? = null
            for (path in paths) {
                try {
                    var st = printJob(p, path, data, mime, job)
                    if (st == 0x040A && mime != "application/octet-stream")          // format not supported
                        st = printJob(p, path, data, "application/octet-stream", job)
                    if (st > 0x00FF) throw IOException("IPP status 0x%04X".format(st))
                    return
                } catch (e: HttpStatus) { last = e } // wrong path -> try next
            }
            throw last ?: IOException("IPP failed")
        }

        class HttpStatus(code: Int) : IOException("HTTP $code")

        private fun printJob(p: NetPrinter, path: String, data: ByteArray, mime: String, job: String): Int {
            val rp = path.trimStart('/')
            val head = ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply {
                    writeShort(0x0101); writeShort(0x0002); writeInt(Random.nextInt(1, 1_000_000))
                    writeByte(0x01)                                    // operation-attributes
                    attr(0x47, "attributes-charset", "utf-8")
                    attr(0x48, "attributes-natural-language", "en")
                    attr(0x45, "printer-uri", "ipp://${p.host}:${p.port}/$rp")
                    attr(0x42, "requesting-user-name", "android")
                    attr(0x42, "job-name", job)
                    attr(0x49, "document-format", mime)
                    writeByte(0x03)                                    // end-of-attributes
                }
            }.toByteArray()
            val c = URL("http://${p.host}:${p.port}/$rp").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 5000; c.readTimeout = 90000
                c.setRequestProperty("Content-Type", "application/ipp")
                c.setFixedLengthStreamingMode(head.size + data.size)
                c.outputStream.use { it.write(head); it.write(data) }
                if (c.responseCode != 200) throw HttpStatus(c.responseCode)
                val r = c.inputStream.use { it.readBytes() }
                return ((r[2].toInt() and 0xff) shl 8) or (r[3].toInt() and 0xff)
            } finally { c.disconnect() }
        }

        private fun DataOutputStream.attr(tag: Int, name: String, value: String) {
            val n = name.toByteArray(); val v = value.toByteArray(Charsets.UTF_8)
            writeByte(tag); writeShort(n.size); write(n); writeShort(v.size); write(v)
        }
    }
}
