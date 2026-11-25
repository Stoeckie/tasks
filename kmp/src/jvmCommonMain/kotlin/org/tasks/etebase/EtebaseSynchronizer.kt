package org.tasks.etebase

import co.touchlab.kermit.Logger
import com.etebase.client.Collection
import com.etebase.client.Item
import com.etebase.client.exceptions.ConflictException
import com.etebase.client.exceptions.ConnectionException
import com.etebase.client.exceptions.HttpException
import com.etebase.client.exceptions.PermissionDeniedException
import com.etebase.client.exceptions.ServerErrorException
import com.etebase.client.exceptions.TemporaryServerErrorException
import com.etebase.client.exceptions.UnauthorizedException
import org.tasks.analytics.AnalyticsEvents.INITIAL_SYNC_COMPLETE
import org.tasks.analytics.AnalyticsEvents.PARAM_SERVER_TYPE
import org.tasks.analytics.AnalyticsEvents.PARAM_TASK_COUNT
import org.tasks.analytics.AnalyticsEvents.PARAM_TYPE
import org.tasks.analytics.Constants
import org.tasks.analytics.Reporting
import org.tasks.analytics.etebaseServerName
import org.tasks.broadcast.RefreshBroadcaster
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.caldav.iCalendar.Companion.fromVtodo
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.DirtyDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_ETEBASE
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.etebase.EtebaseAttachments.Companion.parse
import org.tasks.etebase.EtebaseClient.Companion.isAttachment
import org.tasks.etebase.EtebaseClient.Companion.isTransient
import org.tasks.icalendar.VTodo
import org.tasks.resources.getString
import org.tasks.service.TaskDeleter
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.password_required
import tasks.kmp.generated.resources.subscription_required

class EtebaseSynchronizer(
    private val caldavDao: CaldavDao,
    private val dirtyDao: DirtyDao,
    private val refreshBroadcaster: RefreshBroadcaster,
    private val taskDeleter: TaskDeleter,
    private val clientProvider: EtebaseClientProvider,
    private val iCal: iCalendar,
    private val vtodoCache: VtodoCache,
    private val reporting: Reporting,
    private val attachments: EtebaseAttachments,
) {

    suspend fun sync(account: CaldavAccount, hasPro: Boolean) {
        Logger.d("EtebaseSynchronizer") { "Synchronizing $account" }
        if (!hasPro) {
            setError(account, getString(Res.string.subscription_required))
            return
        }
        if (account.password.isNullOrEmpty()) {
            setError(account, getString(Res.string.password_required))
            return
        }
        try {
            synchronize(account)
            if (account.lastSync == 0L) {
                val taskCount = caldavDao.getTaskCountForAccount(account.uuid!!)
                reporting.logEvent(
                    INITIAL_SYNC_COMPLETE,
                    PARAM_TYPE to Constants.SYNC_TYPE_ETEBASE,
                    PARAM_SERVER_TYPE to account.etebaseServerName,
                    PARAM_TASK_COUNT to taskCount
                )
            }
            account.lastSync = currentTimeMillis()
            setError(account, "")
        } catch (e: ConnectionException) {
            setError(account, e)
        } catch (e: PermissionDeniedException) {
            setError(account, e)
        } catch (e: ServerErrorException) {
            setError(account, e)
        } catch (e: TemporaryServerErrorException) {
            setError(account, e)
        } catch (e: UnauthorizedException) {
            setError(account, e)
        } catch (e: Exception) {
            setError(account, e)
            reporting.reportException(e)
        }
    }

    private suspend fun synchronize(account: CaldavAccount) {
        val client = clientProvider.forAccount(account)
        val collections = client.getCollections()
        val uids = collections.map { it.uid }
        Logger.d("EtebaseSynchronizer") { "Found uids: $uids" }
        for (calendar in caldavDao.findDeletedCalendars(account.uuid!!, uids)) {
            taskDeleter.delete(calendar)
        }
        // tasks with an upload that the server refused are tried again after a while
        dirtyDao.setDirty(attachments.uploadsDue(), listOf(TYPE_ETEBASE))
        for (collection in collections) {
            val uid = collection.uid
            var calendar = caldavDao.getCalendarByUrl(account.uuid!!, uid)
            val meta = collection.meta
            val color = meta.color
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val parsed = java.lang.Long.decode(it).toInt()
                    if (it.length == 7) parsed or 0xFF000000.toInt() else parsed
                }
                ?: 0
            if (calendar == null) {
                calendar = CaldavCalendar(
                    name = meta.name,
                    account = account.uuid,
                    url = collection.uid,
                    uuid = UUIDHelper.newUUID(),
                    color = color,
                )
                caldavDao.insert(calendar)
            } else if (calendar.name != meta.name || calendar.color != color) {
                calendar.name = meta.name
                calendar.color = color
                caldavDao.update(calendar)
                refreshBroadcaster.broadcastRefresh()
            }
            try {
                fetchChanges(account, client, calendar, collection)
                pushLocalChanges(account, client, calendar, collection)
            } catch (e: Exception) {
                if (e.isTransient) throw e
                // one list never stops the sync of the others
                Logger.e("EtebaseSynchronizer", e) { "Not synchronized: ${calendar.name}" }
            }
        }
    }

    private suspend fun setError(account: CaldavAccount, e: Throwable) =
            setError(account, e.message)

    private suspend fun setError(account: CaldavAccount, message: String?) {
        account.error = message
        caldavDao.update(account)
        refreshBroadcaster.broadcastRefresh()
        if (!message.isNullOrEmpty()) {
            Logger.e("EtebaseSynchronizer") { message }
        }
    }

    private suspend fun fetchChanges(
        account: CaldavAccount,
        client: EtebaseClient,
        caldavCalendar: CaldavCalendar,
        collection: Collection
    ) {
        retryDownloads(client, caldavCalendar, collection)
        if (caldavCalendar.ctag?.equals(collection.stoken) == true) {
            Logger.d("EtebaseSynchronizer") { "${caldavCalendar.name} up to date" }
            return
        }
        Logger.d("EtebaseSynchronizer") { "updating $caldavCalendar" }
        client.fetchItems(collection, caldavCalendar) { (stoken, items) ->
            val applied = items.map { item ->
                try {
                    applyRemote(account, client, collection, caldavCalendar, item)
                } catch (e: Exception) {
                    if (e.isTransient) throw e
                    // one bad item never stops the sync of the others
                    Logger.e("EtebaseSynchronizer", e) { "Skipped item ${item.uid}" }
                    item
                }
            }
            client.updateCache(collection, applied)
            stoken?.let {
                caldavCalendar.ctag = it
                caldavDao.update(caldavCalendar)
            }
        }
        Logger.d("EtebaseSynchronizer") { "UPDATE $caldavCalendar" }
        caldavDao.update(caldavCalendar)
        Logger.d("EtebaseSynchronizer") { "Updating parents for ${caldavCalendar.uuid}" }
        caldavDao.updateParents(caldavCalendar.uuid!!)
        refreshBroadcaster.broadcastRefresh()
    }

    /** Applies a listed [item] (without content) and returns it as it is to be cached. */
    private suspend fun applyRemote(
        account: CaldavAccount,
        client: EtebaseClient,
        collection: Collection,
        caldavCalendar: CaldavCalendar,
        listed: Item,
    ): Item {
        if (listed.isAttachment || client.isCached(collection, listed)) {
            return listed // attachments are fetched only by a device that needs the file
        }
        val item = if (listed.isMissingContent) client.fetch(collection, listed.uid) else listed
        val vtodo = item.contentString
        val remote = fromVtodo(vtodo) ?: return item
        val remoteId = remote.uid ?: return item
        val caldavTask = caldavDao.getTaskByRemoteId(caldavCalendar.uuid!!, remoteId)
        if (item.isDeleted) {
            if (caldavTask != null) {
                if (caldavTask.isDeleted()) {
                    caldavDao.delete(caldavTask)
                } else {
                    taskDeleter.delete(caldavTask.task)
                }
            }
            return item
        }
        caldavTask?.obj = item.uid
        val base = caldavTask?.let { vtodoCache.getVtodo(caldavCalendar, it) }?.let { fromVtodo(it) }
        iCal.fromVtodo(account, caldavCalendar, caldavTask, remote, vtodo, item.uid, null, cacheVtodo = false)
        caldavDao.getTaskByRemoteId(caldavCalendar.uuid!!, remoteId)
            ?.takeIf { !it.isDeleted() }
            ?.let { saved ->
                attachments.applyRemote(saved.task, parse(remote), base?.let(::parse).orEmpty(), download(client, collection))
                // the new base only once the attachments are applied: a download that is cut off leaves the old one
                vtodoCache.putVtodo(caldavCalendar, saved, vtodo)
            }
        return item
    }

    private fun download(client: EtebaseClient, collection: Collection): suspend (AttachmentRef) -> ByteArray? = {
        try {
            client.fetchContent(collection, it.uid)?.let(EtebaseAttachments::fromItemContent)
        } catch (e: ServerErrorException) {
            null // the server cannot give this one (it answers 500 for an unknown uid); tried again later
        }
    }

    /** Downloads that failed in [caldavCalendar] are tried again, also when nothing changed on the server. */
    private suspend fun retryDownloads(client: EtebaseClient, caldavCalendar: CaldavCalendar, collection: Collection) {
        for (task in attachments.downloadsDue()) {
            val rows = caldavDao.getTasks(task)
            if (rows.none { !it.isDeleted() }) attachments.forget(task)
            val caldavTask = rows.firstOrNull { it.calendar == caldavCalendar.uuid && !it.isDeleted() } ?: continue
            val refs = vtodoCache.getVtodo(caldavCalendar, caldavTask)?.let { fromVtodo(it) }?.let(::parse) ?: continue
            attachments.applyRemote(task, refs, refs, download(client, collection))
        }
    }

    private class Change(
        val item: Item,
        val caldavTask: CaldavTask,
        val vtodo: String? = null,
        val attachments: List<Item> = emptyList(),
        val dirtyVersion: Long? = null,
        val uploads: List<String> = emptyList(),
    )

    private suspend fun pushLocalChanges(
        account: CaldavAccount,
        client: EtebaseClient,
        caldavCalendar: CaldavCalendar,
        collection: Collection
    ) {
        val changes = ArrayList<Change>()
        for (caldavTask in caldavDao.getMoved(caldavCalendar.uuid!!)) {
            if (isMovePending(account, caldavTask)) {
                continue // the old item stays until the new list has the task and its attachments
            }
            try {
                movedAway(account, client, collection, caldavTask)?.let(changes::add)
            } catch (e: Exception) {
                if (e.isTransient) throw e
                Logger.e("EtebaseSynchronizer", e) { "Old item kept: task ${caldavTask.task}" }
            }
        }
        for (toPush in dirtyDao.getTasksToPush(caldavCalendar.uuid!!)) {
            val caldavTask = caldavDao.getCaldavTaskById(toPush.caldavTaskId) ?: continue
            var change: Change? = null
            try {
                change = push(account, client, caldavCalendar, collection, toPush.task, caldavTask, toPush.dirtyVersion)
                when {
                    change == null -> Unit
                    change.attachments.any { !it.isDeleted } -> upload(client, collection, caldavCalendar, listOf(change))
                    else -> changes.add(change)
                }
            } catch (e: Exception) {
                if (e.isTransient) throw e
                if (e is HttpException) {
                    // e.g. 413 from a proxy: pushed without these files next time, shown in the editor
                    change?.uploads?.let { attachments.notUploaded(toPush.task.id, it) }
                }
                // one task never stops the push of the others; it stays dirty
                Logger.e("EtebaseSynchronizer", e) { "Not pushed: task ${toPush.task.id}" }
            }
        }
        upload(client, collection, caldavCalendar, changes)
    }

    private suspend fun push(
        account: CaldavAccount,
        client: EtebaseClient,
        caldavCalendar: CaldavCalendar,
        collection: Collection,
        task: Task,
        caldavTask: CaldavTask,
        dirtyVersion: Long,
    ): Change? {
        val items = ArrayList<Item>()
        if (task.isDeleted) {
            val vtodo = caldavTask.obj?.let { client.vtodo(caldavCalendar, it) }
            val item = client.deleteItem(collection, caldavTask)
                ?: run {
                    taskDeleter.delete(task)
                    return null
                }
            vtodo?.let { fromVtodo(it) }?.let(::parse)?.map { it.uid }?.forEach { uid ->
                client.deleteAttachment(collection, uid)?.let(items::add)
            }
            return Change(item, caldavTask, attachments = items)
        }
        val cached = vtodoCache.getVtodo(caldavCalendar, caldavTask)
        val model = cached?.let { fromVtodo(it) } ?: VTodo()
        // until the old item of a moved task is gone, what it references is carried (also what was added meanwhile)
        val moved = movedFrom(account, client, task)
        val uploads = ArrayList<String>()
        attachments.applyLocal(
            task = task.id,
            vtodo = model,
            carried = moved?.second.orEmpty(),
            create = { id, content ->
                uploads.add(id)
                client.createAttachment(collection, content).also(items::add).uid
            },
            delete = { uid -> client.deleteAttachment(collection, uid)?.let(items::add) },
            copy = { ref -> client.fetchContent(moved!!.first, ref.uid) },
        )
        val vtodo = iCal.toVtodo(account, caldavTask, task, model)
        val item = client.updateItem(collection, caldavTask, vtodo)
        return Change(item, caldavTask, vtodo.decodeToString(), items, dirtyVersion, uploads)
    }

    /**
     * The deletion of the old item of a task that was moved from this list, with the attachment items that the new
     * list has copies of. When the old item references an attachment that the new one lacks (added on another device
     * during the move), the task is pushed to the new list again first, and the old item stays until then.
     */
    private suspend fun movedAway(
        account: CaldavAccount,
        client: EtebaseClient,
        collection: Collection,
        caldavTask: CaldavTask,
    ): Change? {
        val old = caldavTask.obj?.let { client.fetchOrNull(collection, it) }?.takeIf { !it.isDeleted }
            ?: run {
                caldavDao.delete(caldavTask)
                return null
            }
        // an old item that cannot be read has nothing to carry
        val refs = runCatching { fromVtodo(old.contentString) }.getOrNull()?.let(::parse).orEmpty()
        val leftBehind = when (val moved = movedTo(account, caldavTask)) {
            null -> emptyList() // another account or kind of list: the old items stay
            else -> attachments.leftBehind(caldavTask.task, refs, moved)
        }
        if (leftBehind == null) {
            dirtyDao.setDirty(listOf(caldavTask.task), listOf(TYPE_ETEBASE))
            return null
        }
        return Change(
            client.delete(old),
            caldavTask,
            attachments = leftBehind.mapNotNull { client.deleteAttachment(collection, it) },
        )
    }

    /** The references of the task of a moved [caldavTask] in another list of [account], or null if it has none. */
    private suspend fun movedTo(account: CaldavAccount, caldavTask: CaldavTask): List<AttachmentRef>? {
        for (row in caldavDao.getTasks(caldavTask.task).filter { !it.isDeleted() }) {
            val calendar = caldavDao.getCalendar(row.calendar!!)?.takeIf { it.account == account.uuid } ?: continue
            return vtodoCache.getVtodo(calendar, row)?.let { fromVtodo(it) }?.let(::parse).orEmpty()
        }
        return null
    }

    /**
     * The list a task of this list was moved from on this device (same account), with the attachments its old item
     * references now, so that they can be carried over.
     */
    private suspend fun movedFrom(
        account: CaldavAccount,
        client: EtebaseClient,
        task: Task,
    ): Pair<Collection, List<AttachmentRef>>? {
        for (old in caldavDao.getTasks(task.id).filter { it.isDeleted() && it.obj != null }) {
            val calendar = caldavDao.getCalendar(old.calendar!!)?.takeIf { it.account == account.uuid } ?: continue
            val collection = client.collection(calendar) ?: continue
            val refs = client.vtodo(calendar, old.obj!!)?.let { fromVtodo(it) }?.let(::parse) ?: continue
            return collection to refs
        }
        return null
    }

    /** Whether the task of a moved [caldavTask] waits for its first push to another list of [account]. */
    private suspend fun isMovePending(account: CaldavAccount, caldavTask: CaldavTask): Boolean =
        caldavDao.getTasks(caldavTask.task).any {
            !it.isDeleted() &&
                dirtyDao.isDirty(it.id) == true &&
                caldavDao.getCalendar(it.calendar!!)?.account == account.uuid
        }

    private suspend fun upload(
        client: EtebaseClient,
        collection: Collection,
        caldavCalendar: CaldavCalendar,
        changes: List<Change>,
    ) {
        if (changes.isEmpty()) return
        // attachment items first: a device that lists them in this order has them before their reference
        val items = changes.flatMap { it.attachments } + changes.map { it.item }
        try {
            client.uploadChanges(collection, items)
        } catch (e: ConflictException) {
            Logger.w("EtebaseSynchronizer") { "Changed on the server meanwhile, pushed after the next fetch" }
            return
        }
        for (change in changes) {
            val caldavTask = change.caldavTask
            when {
                change.item.isDeleted && caldavTask.isDeleted() -> caldavDao.delete(caldavTask)
                change.item.isDeleted -> {
                    taskDeleter.delete(caldavTask.task)
                    attachments.forget(caldavTask.task)
                }
                else -> {
                    vtodoCache.putVtodo(caldavCalendar, caldavTask, change.vtodo)
                    caldavDao.update(caldavTask)
                    change.dirtyVersion?.let { dirtyDao.markPushed(caldavTask.id, it) }
                    attachments.pushed(
                        task = caldavTask.task,
                        uploaded = change.uploads,
                        removalsDone = caldavDao.getTasks(caldavTask.task).none { it.isDeleted() },
                    )
                }
            }
        }
        client.updateCache(collection, items)
    }
}
