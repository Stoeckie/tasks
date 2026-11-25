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
import com.etebase.client.exceptions.HttpException
import com.etebase.client.exceptions.NotFoundException
import com.etebase.client.exceptions.PermissionDeniedException
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

    /**
     * Lists changed items. Attachment items come without content (they are fetched only when needed, see [fetch]);
     * a page without them is listed again with content when more than one of its items is needed.
     */
    suspend fun fetchItems(
        collection: Collection,
        calendar: CaldavCalendar,
        callback: suspend (Pair<String?, List<Item>>) -> Unit
    ) {
        val itemManager = itemManager(collection)
        var stoken = calendar.ctag
        do {
            val options = { FetchOptions().stoken(stoken).limit(MAX_FETCH) }
            var page = withContext(Dispatchers.IO) { itemManager.list(options().prefetch(PrefetchOption.Medium)) }
            val attachments = page.data.count { runCatching { it.isAttachment }.getOrDefault(false) }
            if (attachments == 0 && page.data.count { !isCached(collection, it) } > 1) {
                page = withContext(Dispatchers.IO) { itemManager.list(options()) }
            }
            stoken = page.stoken
            callback(Pair(stoken, page.data.toList()))
        } while (!page.isDone)
    }

    /** Whether this device has seen (or pushed) this revision of [item] already. */
    suspend fun isCached(collection: Collection, item: Item): Boolean =
        cache.itemGet(itemManager(collection), collection.uid, item.uid)?.etag == item.etag

    /** The current revision of an item, with content. */
    suspend fun fetch(collection: Collection, uid: String): Item = withContext(Dispatchers.IO) {
        itemManager(collection).fetch(uid, null)
    }

    /** The content of item [uid], or null when there is no such item. */
    suspend fun fetchContent(collection: Collection, uid: String): ByteArray? =
        try {
            val item = fetch(collection, uid)
            item.content.also { check(item.uid == uid) } // the item stays reachable until its content is read
        } catch (e: NotFoundException) {
            null
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

    /** Attachment item [uid] marked deleted, to be uploaded, or null when it is gone already. */
    suspend fun deleteAttachment(collection: Collection, uid: String): Item? =
        try {
            withContext(Dispatchers.IO) {
                itemManager(collection)
                    .fetch(uid, FetchOptions().prefetch(PrefetchOption.Medium))
                    .takeIf { !it.isDeleted }
                    ?.apply { delete() }
            }
        } catch (e: NotFoundException) {
            null
        }

    /** The content of item [obj] in the list [calendar], or null when there is none. */
    suspend fun vtodo(calendar: CaldavCalendar, obj: String): String? {
        val collection = collection(calendar) ?: return null
        return fetchContent(collection, obj)?.decodeToString()
    }

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
                ?.apply {
                    meta = updateMtime(meta)
                    delete()
                }
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
        val Item.isAttachment: Boolean
            get() = meta.itemType == EtebaseAttachments.ITEM_TYPE

        /** Errors that end a sync (it is tried again later), unlike errors caused by one item. */
        val Throwable.isTransient: Boolean
            get() = this is ConnectionException || this is HttpException || this is ServerErrorException ||
                this is TemporaryServerErrorException || this is UnauthorizedException ||
                this is PermissionDeniedException || this is java.io.IOException

        private const val TYPE_TASKS = "etebase.vtodo"
        private const val MAX_FETCH = 30L

        private fun Int.toHexColor(): String? = takeIf { this != 0 }?.let {
            java.lang.String.format("#%06X", 0xFFFFFF and it)
        }
    }
}
