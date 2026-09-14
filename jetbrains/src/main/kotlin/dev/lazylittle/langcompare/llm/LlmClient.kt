package dev.lazylittle.langcompare.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

/** Minimal OpenAI-format chat-completions client with optional SSE streaming. */
object LlmClient {
    private val LOG = Logger.getInstance(LlmClient::class.java)
    private val gson = Gson()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "lang-compare-llm").apply { isDaemon = true }
    }

    /**
     * Proxy resolution order: explicit plugin setting ("direct" forces no proxy) → IDE proxy settings
     * → JVM system properties. Clients are cached per proxy setting so TCP/TLS connections are reused
     * across translations. Returns the client plus a human-readable connection mode for diagnostics.
     */
    private fun buildHttpClient(proxySetting: String): Pair<HttpClient, String> =
        clientCache.computeIfAbsent(proxySetting.trim()) { key -> buildClient(key) }

    private fun buildClient(proxySetting: String): Pair<HttpClient, String> {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
        if (proxySetting.trim().lowercase() in FORCED_DIRECT_KEYWORDS) {
            builder.proxy(HttpClient.Builder.NO_PROXY)
            return builder.build() to "direct"
        }
        parseProxy(proxySetting)?.let {
            builder.proxy(ProxySelector.of(it))
            return builder.build() to "proxy ${it.hostString}:${it.port}"
        }
        val cfg = runCatching { HttpConfigurable.getInstance() }.getOrNull()
        if (cfg != null && cfg.USE_HTTP_PROXY && !cfg.PROXY_HOST.isNullOrBlank() && cfg.PROXY_PORT > 0) {
            val addr = InetSocketAddress(cfg.PROXY_HOST.trim(), cfg.PROXY_PORT)
            builder.proxy(ProxySelector.of(addr))
            return builder.build() to "IDE proxy ${addr.hostString}:${addr.port}"
        }
        val systemSelector = ProxySelector.getDefault()
        if (systemSelector != null) {
            builder.proxy(systemSelector)
            return builder.build() to "system proxy settings"
        }
        return builder.build() to "direct"
    }

    /** Accepts "http://host:port" or "host:port". SOCKS is not supported by ProxySelector.of. */
    private fun parseProxy(setting: String): InetSocketAddress? {
        val raw = setting.trim()
        if (raw.isEmpty()) return null
        val scheme = raw.substringBefore("://", "")
        val hostPort = if (scheme == "http" || scheme == "https") raw.substringAfter("://") else raw
        if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") return null
        val host = hostPort.substringBeforeLast(':').trim()
        val port = hostPort.substringAfterLast(':').trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return InetSocketAddress(host, port)
    }

    private val FORCED_DIRECT_KEYWORDS = setOf("direct", "none", "off")
    private val clientCache = ConcurrentHashMap<String, Pair<HttpClient, String>>()

    class Cancellable(private val future: Future<*>, private val streamRef: AtomicReference<InputStream?>) {
        fun cancel() {
            streamRef.get()?.let { runCatching { it.close() } }
            future.cancel(true)
        }
    }

    fun chat(
        baseUrl: String,
        apiKey: String,
        proxy: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        streaming: Boolean,
        disableThinking: Boolean,
        onDelta: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ): Cancellable {
        val (client, connectionMode) = buildHttpClient(proxy)
        val futureRef = AtomicReference<Future<*>?>(null)
        val streamRef = AtomicReference<InputStream?>(null)
        val future = executor.submit(
            Runnable {
                try {
                    val full = if (streaming) {
                        doStreaming(client, baseUrl, apiKey, model, systemPrompt, userPrompt, streamRef, disableThinking, onDelta)
                    } else {
                        doBlocking(client, baseUrl, apiKey, model, systemPrompt, userPrompt, disableThinking)
                    }
                    onSuccess(full)
                } catch (e: InterruptedException) {
                    LOG.info("LLM request cancelled")
                } catch (e: Exception) {
                    if (futureRef.get()?.isCancelled != true) {
                        onError("${describe(e)}  [connection: $connectionMode]")
                    }
                }
            }
        )
        futureRef.set(future)
        return Cancellable(future, streamRef)
    }

    private fun doStreaming(
        client: HttpClient,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        streamRef: AtomicReference<InputStream?>,
        disableThinking: Boolean,
        onDelta: (String) -> Unit,
    ): String {
        val request = requestBuilder(baseUrl, apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestJson(model, systemPrompt, userPrompt, stream = true, disableThinking = disableThinking)))
            .header("Accept", "text/event-stream")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        streamRef.set(stream)
        stream.use { input ->
            if (response.statusCode() != 200) {
                val err = input.readBytes().toString(Charsets.UTF_8)
                throw IOException("HTTP ${response.statusCode()}: ${err.take(500)}")
            }
            val sb = StringBuilder()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val line = raw.trimEnd('\r', '\n')
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = parseStreamDelta(payload) ?: continue
                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            }
            return sb.toString()
        }
    }

    private fun doBlocking(
        client: HttpClient,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        disableThinking: Boolean,
    ): String {
        val request = requestBuilder(baseUrl, apiKey)
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(requestJson(model, systemPrompt, userPrompt, stream = false, disableThinking = disableThinking)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IOException("HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        val root = JsonParser.parseString(response.body())
        if (!root.isJsonObject) throw IOException("Unexpected non-object response")
        val obj = root.asJsonObject
        apiErrorOrNothing(obj, response.body())
        val choices = obj.getAsJsonArray("choices") ?: throw IOException("No choices in response")
        if (choices.size() == 0) throw IOException("Empty choices in response")
        val message = choices[0].asJsonObject.getAsJsonObject("message")
        val content = message?.get("content")
        return if (content == null || content.isJsonNull) "" else content.asString
    }

    private fun parseStreamDelta(payload: String): String? = try {
        val root = JsonParser.parseString(payload)
        if (!root.isJsonObject) {
            null
        } else {
            val obj = root.asJsonObject
            apiErrorOrNothing(obj, payload)
            val choices = obj.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) {
                ""
            } else {
                val choice = choices[0].asJsonObject
                val content = choice.getAsJsonObject("delta")?.get("content")
                when {
                    content != null && !content.isJsonNull -> content.asString
                    else -> choice.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                }
            }
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun apiErrorOrNothing(obj: com.google.gson.JsonObject, raw: String) {
        val err = obj.get("error") ?: return
        val msg = if (err.isJsonObject) {
            err.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString
        } else {
            null
        }
        throw IOException("API error: ${msg ?: raw.take(300)}")
    }

    private fun requestBuilder(baseUrl: String, apiKey: String): HttpRequest.Builder {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        return builder
    }

    private fun requestJson(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        stream: Boolean,
        disableThinking: Boolean,
    ): String {
        val body = linkedMapOf<String, Any>(
            "model" to model,
            "stream" to stream,
            "messages" to listOf(
                linkedMapOf("role" to "system", "content" to systemPrompt),
                linkedMapOf("role" to "user", "content" to userPrompt),
            ),
        )
        if (disableThinking) {
            // "thinking" is passed directly in the OpenAI-format body; only sent when enabled
            // because strict providers reject unknown fields.
            body["thinking"] = linkedMapOf("type" to "disabled")
        }
        return gson.toJson(body)
    }

    /** Strips a markdown code fence ("```lang ... ```") if present; returns raw text otherwise. */
    fun extractCodeBlock(text: String): String {
        val t = text.trimStart()
        if (!t.startsWith("```")) return t.trimEnd()
        val firstNewline = t.indexOf('\n')
        if (firstNewline < 0) return ""
        var body = t.substring(firstNewline + 1)
        val trimmed = body.trimEnd()
        if (trimmed.endsWith("```")) {
            body = trimmed.dropLast(3).trimEnd()
        }
        return body
    }

    private fun describe(e: Exception): String = when (e) {
        is SSLHandshakeException ->
            "TLS handshake failed (${e.message}). If the endpoint is directly reachable " +
                "(e.g. open.bigmodel.cn), a proxy is likely intercepting the request — fill 'direct' in HTTP Proxy " +
                "or fix the IDE/system proxy. If the endpoint needs a proxy (e.g. api.openai.com), set it in HTTP Proxy."
        is HttpTimeoutException -> "Request timed out: ${e.message}"
        is ConnectException -> "Connection failed: ${e.message}"
        is IOException -> "Network/HTTP error: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
