package com.spoolfid.spoolman

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.Collections

/** A tiny in-process HTTP server that plays Spoolman, recording every request it gets. */
class FakeSpoolman : AutoCloseable {
    class Request(val method: String, val path: String, val query: String?, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Canned answers per "METHOD path"; when several are queued they are served in order and the last repeats. */
    private val responses = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Request(ex.requestMethod, ex.requestURI.path, ex.requestURI.query, body)
            val queue = responses["${ex.requestMethod} ${ex.requestURI.path}"]
            val (code, text) = when {
                queue == null -> 404 to """{"message":"nope"}"""
                queue.size > 1 -> queue.removeFirst()
                else -> queue.first()
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            if (code == 204) {
                ex.sendResponseHeaders(204, -1)
            } else {
                val bytes = text.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
    }

    fun respond(key: String, vararg answers: Pair<Int, String>) {
        responses[key] = ArrayDeque(answers.toList())
    }

    /** The desktop JDK's HttpURLConnection refuses PATCH (Android's accepts it), so tests use the modern client. */
    private val transport = HttpTransport { method, url, body ->
        val publisher = if (body == null) BodyPublishers.noBody() else BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        val response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString())
        HttpResponse(response.statusCode(), response.body())
    }

    fun client() = SpoolmanClient("127.0.0.1:${server.address.port}", transport)

    fun lastBody(method: String, path: String): String = requests.last { it.method == method && it.path == path }.body

    override fun close() = server.stop(0)
}
