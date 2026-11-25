package org.tasks.etebase

import co.touchlab.kermit.Logger
import com.etebase.client.Account
import com.etebase.client.Collection
import com.etebase.client.FetchOptions
import com.etebase.client.Item
import com.etebase.client.ItemManager
import com.etebase.client.ItemMetadata
import com.etebase.client.PrefetchOption
import com.etebase.client.exceptions.ConnectionException
import com.etebase.client.exceptions.ServerErrorException
import com.etebase.client.exceptions.TemporaryServerErrorException
import com.etebase.client.exceptions.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.http.translateExceptions
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class EtebaseClient(
        private val filesDir: String,
        private val username: String,
        private val etebase: Account,
        private val caldavDao: CaldavDao
) : EtebaseAccountClient {
    private val cache = EtebaseLocalCache.getInstance(filesDir, username)

    // Held here so that they stay reachable during native calls: the library frees a native object when its Java
    // object is finalized, which can happen during a call on a temporary.
    private val itemManagers = ConcurrentHashMap<String, ItemManager>()
    private val collectionManager by lazy { etebase.collectionManager }

    private fun itemManager(collection: Collection): ItemManager =
        itemManagers.getOrPut(collection.uid) { collectionManager.getItemManager(collection) }

    override fun getSession(): String = etebase.save(null)

    suspend fun getCollections(): List<Collection> {
        var stoken: String? = cache.loadStoken()
        do {
            val response = withContext(Dispatchers.IO) {
                collectionManager.list(
                        TYPE_TASKS,
                        FetchOptions().stoken(stoken).limit(MAX_FETCH)
                )
            }
            stoken = response.stoken
            response.data
                    .filter { it.collectionType == TYPE_TASKS }
                    .forEach { cache.collectionSet(collectionManager, it) }
            response.removedMemberships.forEach {
                cache.collectionUnset(collectionManager, it)
            }
        } while (!response.isDone)
        stoken?.let { cache.saveStoken(it) }
        return cache.collectionList(collectionManager)
    }

    /** Lists changed items without content: a task item is fetched when it is new here, an attachment when needed. */
    suspend fun fetchItems(
        collection: Collection,
        calendar: CaldavCalendar,
        callback: suspend (Pair<String?, List<Item>>) -> Unit
    ) {
        val itemManager = itemManager(collection)
        var stoken = calendar.ctag
        do {
            val page = withContext(Dispatchers.IO) {
                itemManager.list(FetchOptions().stoken(stoken).limit(MAX_FETCH).prefetch(PrefetchOption.Medium))
            }
            stoken = page.stoken
            callback(Pair(stoken, page.data.toList()))
        } while (!page.isDone)
    }

    /** Whether this device has seen (or pushed) this revision of [item] already. */
    suspend fun isCached(collection: Collection, item: Item): Boolean =
        cache.itemGet(itemManager(collection), collection.uid, item.uid)?.etag == item.etag

    /**
     * Whether this device listed item [uid] in [collection] and it was not deleted then. The server answers 500 for a
     * uid it does not have, so nothing asks it for one this device has not seen.
     */
    suspend fun isSeen(collection: Collection, uid: String): Boolean =
        cache.itemGet(itemManager(collection), collection.uid, uid)?.isDeleted == false

    /** The content of task item [uid] as this device last synced it, if it has it. */
    suspend fun cachedVtodo(collection: Collection, uid: String): String? =
        cache.itemGet(itemManager(collection), collection.uid, uid)
            ?.takeIf { !it.isDeleted && !it.isMissingContent }
            ?.let { runCatching { it.contentString }.getOrNull() }

    /** The current revision of a listed item, with content. */
    suspend fun fetch(collection: Collection, uid: String): Item = withContext(Dispatchers.IO) {
        itemManager(collection).fetch(uid, null)
    }

    /**
     * The content of item [uid] (one this device has seen), or null when it is deleted. A deleted revision keeps its
     * content, so the item is first fetched without it: a deleted attachment is not downloaded. (One deleted between
     * the two requests has been transferred then, and its content is still the file: it is returned.)
     */
    suspend fun fetchContent(collection: Collection, uid: String): ByteArray? {
        val revision = withContext(Dispatchers.IO) {
            itemManager(collection).fetch(uid, FetchOptions().prefetch(PrefetchOption.Medium))
        }
        if (revision.isDeleted) return null
        val item = fetch(collection, uid)
        // holding the monitor keeps the item reachable while its content is read: the library frees the native item
        // when the Java object is finalized (Reference.reachabilityFence needs API 28)
        return synchronized(item) { item.content }
    }

    /** A new attachment item, to be uploaded in the same batch as the VTODO that references it. */
    suspend fun createAttachment(collection: Collection, content: ByteArray): Item = withContext(Dispatchers.IO) {
        itemManager(collection).create(
            ItemMetadata().apply {
                itemType = EtebaseAttachments.ITEM_TYPE
                mtime = currentTimeMillis()
            },
            content,
        )
    }

    /** Attachment item [uid] marked deleted, to be uploaded, or null when this device does not have it (as listed). */
    suspend fun deleteAttachment(collection: Collection, uid: String): Item? =
        cache.itemGet(itemManager(collection), collection.uid, uid)?.takeIf { !it.isDeleted }?.apply { delete() }

    suspend fun collection(calendar: CaldavCalendar): Collection? =
        calendar.url?.let { url ->
            try {
                cache.collectionGet(collectionManager, url)
            } catch (e: Exception) {
                null
            }
        }

    suspend fun updateItem(collection: Collection, task: CaldavTask, content: ByteArray): Item {
        val itemManager = itemManager(collection)
        val obj = task.obj
            ?: run {
                Logger.e("EtebaseClient") { "null obj for caldavTask.id=${task.id}" }
                task.obj = task.remoteId
                task.obj
            }
            ?: throw IllegalStateException("Update failed - missing UUID")
        val item = cache.itemGet(itemManager, collection.uid, obj)
                ?: itemManager
                        .create(ItemMetadata().apply { name = task.remoteId!! }, "")
                        .apply {
                            task.obj = uid
                            caldavDao.update(task)
                        }
        item.meta = updateMtime(item.meta, currentTimeMillis())
        item.content = content
        return item
    }

    suspend fun deleteItem(collection: Collection, task: CaldavTask): Item? {
        val itemManager = itemManager(collection)
        val objId = task.obj
            ?: run {
                Logger.e("EtebaseClient") { "null obj for caldavTask.id=${task.id}" }
                task.obj = task.remoteId
                task.obj
            }
            ?: return null
        return cache.itemGet(itemManager, collection.uid, objId)
                ?.takeIf { !it.isDeleted }
                ?.let(::delete)
    }

    /** [item] marked deleted, to be uploaded. */
    fun delete(item: Item): Item = item.apply {
        meta = updateMtime(meta)
        delete()
    }

    private fun updateMtime(meta: ItemMetadata, mtime: Long = currentTimeMillis()): ItemMetadata =
            meta.also {
                it.mtime = mtime
            }

    suspend fun updateCache(collection: Collection, items: List<Item>) {
        val itemManager = itemManager(collection)
        items.forEach { cache.itemSet(itemManager, collection.uid, it) }
    }

    /** Uploads [items] atomically; fails with a ConflictException when one changed on the server meanwhile. */
    suspend fun uploadChanges(collection: Collection, items: List<Item>) {
        val itemManager = itemManager(collection)
        withContext(Dispatchers.IO) {
            itemManager.transaction(items.toTypedArray())
        }
    }

    override suspend fun logout() {
        try {
            EtebaseLocalCache.clear(filesDir, username)
            withContext(Dispatchers.IO) {
                etebase.logout()
            }
        } catch (e: Exception) {
            Logger.e("EtebaseClient", e) { "logout failed" }
        }
    }

    override suspend fun makeCollection(name: String, color: Int) = translateExceptions {
        collectionManager
                .create(TYPE_TASKS, ItemMetadata(), "")
                .let { setAndUpload(it, name, color) }
    }

    override suspend fun updateCollection(calendar: CaldavCalendar, name: String, color: Int) = translateExceptions {
        cache
                .collectionGet(collectionManager, calendar.url!!)
                .let { setAndUpload(it, name, color) }
    }

    override suspend fun deleteCollection(calendar: CaldavCalendar) = translateExceptions {
        cache
                .collectionGet(collectionManager, calendar.url!!)
                .apply { delete() }
                .let { setAndUpload(it) }
    }

    private suspend fun setAndUpload(
            collection: Collection,
            name: String? = null,
            color: Int? = null
    ): String {
        collection.meta = collection.meta.let { meta ->
            name?.let { meta.name = it }
            color?.let { meta.color = it.toHexColor() }
            meta.mtime = currentTimeMillis()
            meta
        }
        withContext(Dispatchers.IO) {
            collectionManager.upload(collection)
        }
        cache.collectionSet(collectionManager, collection)
        return collection.uid
    }

    companion object {
        /** False also for an item whose metadata cannot be read (handled, and skipped, as a task). */
        val Item.isAttachment: Boolean
            get() = try {
                meta.itemType == EtebaseAttachments.ITEM_TYPE
            } catch (e: Exception) {
                false
            }

        /**
         * Errors after which an operation is tried again by a later sync (a 500 also ends the list or task it occurred
         * in, see [endsSync]), unlike errors caused by one item, which is skipped (also any other HTTP status, such as
         * 413 from a proxy for a large upload).
         */
        val Throwable.isTransient: Boolean
            get() = this is ServerErrorException || endsSync

        /** Errors that end the sync of the account: no connection, server unavailable, login, app stopped. */
        val Throwable.endsSync: Boolean
            get() = this is ConnectionException || this is TemporaryServerErrorException ||
                this is UnauthorizedException || this is java.io.IOException || this is CancellationException

        private const val TYPE_TASKS = "etebase.vtodo"
        private const val MAX_FETCH = 30L

        private fun Int.toHexColor(): String? = takeIf { this != 0 }?.let {
            java.lang.String.format("#%06X", 0xFFFFFF and it)
        }
    }
}
