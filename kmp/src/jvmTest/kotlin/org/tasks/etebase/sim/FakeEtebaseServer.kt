package org.tasks.etebase.sim

import java.net.URI
import java.net.URLDecoder

/** An HTTP exchange with an etebase server: status and body, or an IOException for a network error. */
fun interface Transport {
    fun exchange(method: String, url: String, token: String?, body: ByteArray?): Pair<Int, ByteArray>
}

/**
 * In-memory etebase server: the parts of the API of etesync/server (etebase_server/fastapi, commit 6ac54121) that the
 * etebase client library uses for Tasks, with the same semantics where the app relies on them:
 * - stokens: one per revision, listing in stoken order with `limit` and `done`, the collection stoken is its newest
 *   revision's;
 * - transactions are atomic and check etags (409 "item_failed"), re-uploading the current revision is a success,
 *   `batch` does not check etags; new chunks need their content (chunks are shared by uid, as on the server);
 * - an item uid that the collection does not have answers 500 (the server's unhandled DoesNotExist);
 * - `prefetch=medium` lists chunks without content.
 * The server sees only encrypted data, as the real one.
 */
class FakeEtebaseServer : Transport {
    private class Revision(val uid: String, val meta: ByteArray, val deleted: Boolean, val chunks: List<String>, val stoken: Long)

    private class StoredItem(val uid: String, val version: Long, val encryptionKey: ByteArray?, var current: Revision)

    private class StoredCollection(
        val uid: String,
        val type: ByteArray?,
        val key: ByteArray,
        val members: MutableSet<String>,
        val memberStoken: Long,
        val main: StoredItem,
    ) {
        val items = LinkedHashMap<String, StoredItem>()
        fun stoken() = maxOf(memberStoken, main.current.stoken, items.values.maxOfOrNull { it.current.stoken } ?: 0)
    }

    private class HttpError(val status: Int, val code: String, val detail: String) : Exception(detail)

    private var stokens = 0L
    private val stokenUids = HashMap<Long, String>()
    private val stokenIds = HashMap<String, Long>()
    private val users = HashMap<String, Map<String, Any?>>() // token -> login response user
    private val collections = LinkedHashMap<String, StoredCollection>()
    private val chunks = HashMap<String, ByteArray>()

    /** Number of requests answered so far. */
    var requests = 0
        private set

    private fun newStoken(): Long = (++stokens).also { id ->
        "st$id".let { stokenUids[id] = it; stokenIds[it] = id }
    }

    @Synchronized
    override fun exchange(method: String, url: String, token: String?, body: ByteArray?): Pair<Int, ByteArray> {
        requests++
        return try {
            val uri = URI(url)
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2) + ""
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            val path = uri.path.removePrefix("/api/v1/").trim('/').split('/')
            val input = body?.takeIf { it.isNotEmpty() }?.let { Msgpack.decode(it) }
            val (status, out) = route(method, path, query, token, input)
            status to Msgpack.encode(out)
        } catch (e: HttpError) {
            e.status to Msgpack.encode(mapOf("code" to e.code, "detail" to e.detail))
        }
    }

    private fun user(token: String?): String =
        users[token]?.get("username") as? String ?: throw HttpError(401, "bad_token", "Invalid token")

    private fun route(method: String, path: List<String>, query: Map<String, String>, token: String?, input: Any?): Pair<Int, Any?> =
        when {
            method == "GET" && path == listOf("authentication", "is_etebase") -> 200 to null
            method == "POST" && path == listOf("authentication", "signup") -> 201 to signup(input as Map<*, *>)
            method == "POST" && path == listOf("authentication", "logout") -> 204 to null
            path.firstOrNull() != "collection" -> throw HttpError(404, "not_found", "Not found")
            else -> collectionRoute(method, path.drop(1), query, user(token), input)
        }

    private fun signup(body: Map<*, *>): Map<String, Any?> {
        val user = body["user"] as Map<*, *>
        val username = user["username"] as String
        val token = "token-$username-${users.size}"
        users[token] = mapOf(
            "username" to username,
            "email" to user["email"],
            "pubkey" to body["pubkey"],
            "encryptedContent" to body["encryptedContent"],
        )
        return mapOf("token" to token, "user" to users[token])
    }

    private fun collectionRoute(method: String, path: List<String>, query: Map<String, String>, user: String, input: Any?): Pair<Int, Any?> {
        val prefetch = query["prefetch"] != "medium"
        if (path.isEmpty() && method == "POST") return 201 to createCollection(user, input as Map<*, *>)
        if (path == listOf("list_multi") && method == "POST") {
            @Suppress("UNCHECKED_CAST")
            val types = ((input as Map<*, *>)["collectionTypes"] as List<ByteArray>)
            return 200 to listCollections(user, types, query["stoken"], query["limit"]?.toInt() ?: 50, prefetch)
        }
        val collection = collections[path[0]]?.takeIf { user in it.members }
            ?: throw HttpError(404, "not_found", "No collection")
        return when {
            path.size == 1 && method == "GET" -> 200 to collectionOut(collection, prefetch)
            path.size == 2 && path[1] == "item" && method == "GET" ->
                200 to listItems(collection, query["stoken"], query["limit"]?.toInt() ?: 50, prefetch)
            path.size == 3 && path[2] == "transaction" && method == "POST" -> 200 to upload(collection, input as Map<*, *>, true)
            path.size == 3 && path[2] == "batch" && method == "POST" -> 200 to upload(collection, input as Map<*, *>, false)
            path.size == 3 && method == "GET" -> {
                // the server's item_get: queryset.get(uid=...) raises DoesNotExist for an unknown uid, answered 500
                val item = (collection.items[path[2]] ?: collection.main.takeIf { it.uid == path[2] })
                    ?: throw HttpError(500, "server_error", "Server error")
                200 to itemOut(item, prefetch)
            }
            path.size == 6 && path[3] == "chunk" && path[5] == "download" && method == "GET" ->
                chunks[path[4]]?.let { 200 to it } ?: throw HttpError(404, "not_found", "No chunk")
            else -> throw HttpError(404, "not_found", "Not found")
        }
    }

    private fun createCollection(user: String, body: Map<*, *>): Any? {
        val item = body["item"] as Map<*, *>
        if (item["etag"] != null) throw HttpError(400, "bad_etag", "etag is not null")
        val uid = item["uid"] as String
        if (collections.containsKey(uid)) throw HttpError(409, "unique_uid", "Collection with this uid already exists")
        val main = StoredItem(uid, item["version"] as Long, item["encryptionKey"] as ByteArray?, revision(item["content"] as Map<*, *>))
        collections[uid] = StoredCollection(uid, body["collectionType"] as ByteArray?, body["collectionKey"] as ByteArray, mutableSetOf(user), newStoken(), main)
        return null
    }

    private fun listCollections(user: String, types: List<ByteArray>, stoken: String?, limit: Int, prefetch: Boolean): Map<String, Any?> {
        val since = stokenId(stoken)
        val changed = collections.values
            .filter { user in it.members && (it.type == null || types.any { t -> t.contentEquals(it.type) }) }
            .filter { it.stoken() > since }
            .sortedBy { it.stoken() }
        val result = changed.take(limit)
        return mapOf(
            "data" to result.map { collectionOut(it, prefetch) },
            "stoken" to (result.maxOfOrNull { it.stoken() } ?: since.takeIf { it > 0 })?.let { stokenUids[it] },
            "done" to (changed.size <= limit),
        )
    }

    private fun collectionOut(c: StoredCollection, prefetch: Boolean) = mapOf(
        "collectionType" to c.type,
        "collectionKey" to c.key,
        "accessLevel" to 1L, // admin
        "stoken" to stokenUids[c.stoken()],
        "item" to itemOut(c.main, prefetch),
    )

    private fun listItems(c: StoredCollection, stoken: String?, limit: Int, prefetch: Boolean): Map<String, Any?> {
        val since = stokenId(stoken)
        val changed = c.items.values.filter { it.current.stoken > since }.sortedBy { it.current.stoken }
        val result = changed.take(limit)
        return mapOf(
            "data" to result.map { itemOut(it, prefetch) },
            "stoken" to (result.maxOfOrNull { it.current.stoken } ?: since.takeIf { it > 0 })?.let { stokenUids[it] },
            "done" to (changed.size <= limit),
        )
    }

    private fun stokenId(stoken: String?): Long =
        stoken?.let { stokenIds[it] ?: throw HttpError(400, "bad_stoken", "Invalid stoken.") } ?: 0

    private fun itemOut(item: StoredItem, prefetch: Boolean) = mapOf(
        "uid" to item.uid,
        "version" to item.version,
        "encryptionKey" to item.encryptionKey,
        "content" to mapOf(
            "uid" to item.current.uid,
            "meta" to item.current.meta,
            "deleted" to item.current.deleted,
            "chunks" to item.current.chunks.map { listOf(it, if (prefetch) chunks[it] else null) },
        ),
    )

    /** The server's item_bulk_common: everything or nothing. */
    private fun upload(c: StoredCollection, body: Map<*, *>, validateEtag: Boolean): Any? {
        @Suppress("UNCHECKED_CAST")
        val items = body["items"] as List<Map<*, *>>
        @Suppress("UNCHECKED_CAST")
        val deps = body["deps"] as List<Map<*, *>>?
        val conflicts = ArrayList<String>()
        deps?.forEach { dep ->
            val item = collections.values.firstNotNullOfOrNull { it.items[dep["uid"]] }
                ?: throw HttpError(500, "server_error", "Server error")
            if (item.current.uid != dep["etag"]) conflicts.add(dep["uid"] as String)
        }
        val newChunks = HashMap<String, ByteArray>()
        val apply = ArrayList<() -> Unit>()
        for (item in items) {
            val uid = item["uid"] as String
            val content = item["content"] as Map<*, *>
            val existing = if (uid == c.main.uid) c.main else c.items[uid]
            val current = existing?.current?.uid
            if (current == content["uid"]) continue // already up to date: a success
            if (validateEtag && current != item["etag"]) {
                conflicts.add(uid)
                continue
            }
            @Suppress("UNCHECKED_CAST")
            for (chunk in content["chunks"] as List<List<Any?>>) {
                val chunkUid = chunk[0] as String
                if (chunks.containsKey(chunkUid) || newChunks.containsKey(chunkUid)) continue
                newChunks[chunkUid] = chunk.getOrNull(1) as ByteArray?
                    ?: throw HttpError(400, "chunk_no_content", "Tried to create a new chunk without content")
            }
            apply.add {
                val revision = revision(content)
                if (existing == null) {
                    c.items[uid] = StoredItem(uid, item["version"] as Long, item["encryptionKey"] as ByteArray?, revision)
                } else {
                    existing.current = revision
                }
            }
        }
        if (conflicts.isNotEmpty()) throw HttpError(409, "item_failed", "Items failed to validate: $conflicts")
        chunks.putAll(newChunks)
        apply.forEach { it() }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun revision(content: Map<*, *>) = Revision(
        uid = content["uid"] as String,
        meta = content["meta"] as ByteArray,
        deleted = content["deleted"] as Boolean,
        chunks = (content["chunks"] as List<List<Any?>>).map { chunk ->
            (chunk[0] as String).also { uid -> (chunk.getOrNull(1) as ByteArray?)?.let { chunks.putIfAbsent(uid, it) } }
        },
        stoken = newStoken(),
    )
}
