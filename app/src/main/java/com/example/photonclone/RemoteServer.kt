@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.example.photonclone

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny HTTP server: serves the control page, a JSON state/set API and an MJPEG live view.
 * Every request must carry ?key=<accessKey>.
 */
class RemoteServer(
    private val key: String,
    private val stateJson: () -> String,
    private val onSet: (Map<String, String>) -> Unit,
) {
    private val lock = java.lang.Object()
    private var frame: ByteArray? = null
    private var seq = 0L
    @Volatile private var closed = false
    @Volatile var clients = 0
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start(port: Int) {
        val ss = ServerSocket(port)
        serverSocket = ss
        pool.execute {
            while (!closed) {
                try { val s = ss.accept(); pool.execute { handle(s) } } catch (e: Exception) { break }
            }
        }
    }

    fun stop() {
        closed = true
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        synchronized(lock) { lock.notifyAll() }
    }

    fun push(jpeg: ByteArray) = synchronized(lock) {
        frame = jpeg; seq++
        lock.notifyAll()
    }

    private fun handle(s: Socket) {
        try {
            s.use {
                val reader = s.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return
                while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
                val target = line.split(" ").getOrNull(1) ?: return
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val out = s.getOutputStream()
                if (query["key"] != key) { respond(out, 403, "text/plain", "Forbidden"); return }
                when (path) {
                    "/" -> respond(out, 200, "text/html; charset=utf-8", WEB_UI)
                    "/api/state" -> respond(out, 200, "application/json", stateJson())
                    "/api/set" -> { onSet(query); respond(out, 200, "application/json", stateJson()) }
                    "/stream" -> stream(out)
                    else -> respond(out, 404, "text/plain", "Not found")
                }
            }
        } catch (_: Exception) { }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun stream(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        clients++
        try {
            var last = 0L
            while (!closed) {
                val f: ByteArray? = synchronized(lock) {
                    if (seq == last) lock.wait(2000)
                    if (seq != last) { last = seq; frame } else null
                }
                if (f != null) {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                    out.write(f); out.write("\r\n".toByteArray()); out.flush()
                }
            }
        } finally { clients-- }
    }
}
