package org.tasks.etebase.sim

import com.etebase.client.HttpClient
import com.etebase.client.Response
import java.io.IOException

/** A request as the device sends it. */
class Request(val n: Int, val method: String, val url: String, val body: ByteArray?) {
    val path: String get() = url.substringAfter("/api/v1/").substringBefore('?')
    val isTransaction get() = method == "POST" && (path.endsWith("/transaction/") || path.endsWith("/batch/"))
    val isItemGet get() = method == "GET" && Regex("collection/[^/]+/item/[^/]+/").matches(path)
    val isList get() = method == "GET" && path.endsWith("/item/")
    override fun toString() = "#$n $method $path ${body?.size ?: 0}B"
}

enum class FaultKind {
    /** Connection error before the request reaches the server. */
    FAIL,
    /** The server applies the request; the reply is lost. */
    LOSE_REPLY,
    /** The server answers 413 without applying anything (a proxy with a body limit). */
    REFUSE,
    /** The server answers 500 once (and applies nothing). */
    SERVER_ERROR,
    /** The app is killed before the request is sent. */
    KILL_BEFORE,
    /** The app is killed after the server applied the request, before the reply arrived. */
    KILL_AFTER,
}

class Fault(val kind: FaultKind, val where: (Request) -> Boolean, val label: String) {
    override fun toString() = "$kind at $label"
}

/**
 * The network of one simulated device: the etebase library calls it ([HttpClient]); it forwards to [transport] (the
 * fake or a real server) and injects faults, goes offline, runs [hooks] (other devices or the user acting while a
 * request is under way), and counts attachment bytes per item uid.
 */
/** Attachment bytes a device moved, per item uid (all items: the caller knows which are attachments). */
class Counters {
    /** Item uid to content bytes of committed uploads. */
    val uploaded = LinkedHashMap<String, Long>()
    /** Item uid to the number of completed downloads with content. */
    val downloaded = LinkedHashMap<String, Int>()
    /** Uploads the server applied whose reply the device did not get (lost, or the app was killed). */
    var lostUploads = 0
    /** Content bytes sent in transactions that the server or a proxy refused. */
    var refusedBytes = 0L
}

class SimHttp(
    private val transport: Transport,
    private val trace: (String) -> Unit = {},
    val counters: Counters = Counters(),
) : HttpClient {
    var offline = false
    var killed = false
    var onKill: () -> Unit = {}
    val faults = ArrayList<Fault>()
    val hooks = ArrayList<Pair<(Request) -> Boolean, () -> Unit>>()
    var requests = 0
        private set
    /** Requests that list collections (more on an account with many collections). */
    var collectionLists = 0
        private set

    val uploaded get() = counters.uploaded
    val downloaded get() = counters.downloaded
    /** Optional filter of listed collections (live server: only the collections of this run). */
    var collectionFilter: ((String) -> Boolean)? = null
    /** Collections this device may only read (a read-only member): transactions answer 403. */
    var readOnly: Set<String> = emptySet()
    /** A proxy that refuses request bodies above this size (413), every time. */
    var proxyLimit: Int? = null

    override fun get(url: String, auth_token: String?, response: Response) = call("GET", url, auth_token, null, response)
    override fun post(url: String, auth_token: String?, body: ByteArray, response: Response) = call("POST", url, auth_token, body, response)
    override fun put(url: String, auth_token: String?, body: ByteArray, response: Response) = call("PUT", url, auth_token, body, response)
    override fun patch(url: String, auth_token: String?, body: ByteArray, response: Response) = call("PATCH", url, auth_token, body, response)
    override fun del(url: String, auth_token: String?, response: Response) = call("DELETE", url, auth_token, null, response)

    private fun call(method: String, url: String, token: String?, body: ByteArray?, response: Response) {
        val request = Request(++requests, method, url, body)
        if (request.path.endsWith("collection/list_multi/")) collectionLists++
        trace("  $request")
        if (killed) return response.reset_err("killed")
        if (offline) return response.reset_err("java.net.UnknownHostException: offline")
        hooks.firstOrNull { it.first(request) }?.let { hook ->
            hooks.remove(hook)
            trace("  hook before $request")
            hook.second()
        }
        if (request.isTransaction && (proxyLimit?.let { (body?.size ?: 0) > it } == true)) {
            counters.refusedBytes += contentBytes(request)
            return response.reset_ok(Msgpack.encode(mapOf("detail" to "Request Entity Too Large")), 413)
        }
        if (request.isTransaction && readOnly.any { request.path.startsWith("collection/$it/") }) {
            counters.refusedBytes += contentBytes(request)
            return response.reset_ok(Msgpack.encode(mapOf("code" to "no_write_access", "detail" to "You need write access to write to this collection")), 403)
        }
        val fault = faults.firstOrNull { it.where(request) }?.also { faults.remove(it) }
        fault?.let { trace("  fault $it: $request") }
        when (fault?.kind) {
            FaultKind.FAIL -> return response.reset_err("java.net.SocketTimeoutException: test")
            FaultKind.REFUSE -> return response.reset_ok(Msgpack.encode(mapOf("detail" to "Request Entity Too Large")), 413)
            FaultKind.SERVER_ERROR -> return response.reset_ok(Msgpack.encode(mapOf("detail" to "Server error")), 500)
            FaultKind.KILL_BEFORE -> return kill(response)
            else -> Unit
        }
        val (status, reply) = try {
            transport.exchange(method, url, token, body)
        } catch (e: IOException) {
            return response.reset_err(e.toString())
        }
        // an upload counts once the server applied it, a download once the device has it
        if (request.isTransaction) account(request, status, reply)
        if (request.isTransaction && status in 200..299 && fault?.kind in listOf(FaultKind.LOSE_REPLY, FaultKind.KILL_AFTER)) {
            counters.lostUploads++
        }
        when (fault?.kind) {
            FaultKind.LOSE_REPLY -> return response.reset_err("java.io.IOException: unexpected end of stream")
            FaultKind.KILL_AFTER -> return kill(response)
            else -> Unit
        }
        if (!request.isTransaction) account(request, status, reply)
        response.reset_ok(filter(request, status, reply), status)
    }

    private fun contentBytes(request: Request): Long {
        @Suppress("UNCHECKED_CAST")
        val items = (Msgpack.decode(request.body!!) as Map<*, *>)["items"] as List<Map<*, *>>
        return items.sumOf { item ->
            @Suppress("UNCHECKED_CAST")
            ((item["content"] as Map<*, *>)["chunks"] as List<List<Any?>>).sumOf { (it.getOrNull(1) as ByteArray?)?.size?.toLong() ?: 0 }
        }
    }

    private fun kill(response: Response) {
        killed = true
        onKill()
        response.reset_err("killed")
    }

    private fun account(request: Request, status: Int, reply: ByteArray) {
        if (status !in 200..299) return
        if (request.isTransaction) {
            @Suppress("UNCHECKED_CAST")
            val items = (Msgpack.decode(request.body!!) as Map<*, *>)["items"] as List<Map<*, *>>
            for (item in items) {
                @Suppress("UNCHECKED_CAST")
                val chunks = (item["content"] as Map<*, *>)["chunks"] as List<List<Any?>>
                val bytes = chunks.sumOf { (it.getOrNull(1) as ByteArray?)?.size?.toLong() ?: 0 }
                if (bytes > 0) uploaded.merge(item["uid"] as String, bytes, Long::plus)
            }
        } else if (request.isItemGet) {
            val item = Msgpack.decode(reply) as Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val chunks = (item["content"] as Map<*, *>)["chunks"] as List<List<Any?>>
            if (chunks.any { it.getOrNull(1) != null }) downloaded.merge(item["uid"] as String, 1, Int::plus)
        }
    }

    private fun filter(request: Request, status: Int, reply: ByteArray): ByteArray {
        val keep = collectionFilter ?: return reply
        if (status != 200 || !request.path.endsWith("collection/list_multi/")) return reply
        val list = Msgpack.decode(reply) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val data = (list["data"] as List<Map<*, *>>).filter { keep((it["item"] as Map<*, *>)["uid"] as String) }
        return Msgpack.encode(LinkedHashMap(list).apply { this["data"] = data })
    }
}
