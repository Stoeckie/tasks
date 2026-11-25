package org.tasks.etebase

import co.touchlab.kermit.Logger
import com.etebase.client.Collection
import com.etebase.client.Item
import com.etebase.client.exceptions.ConflictException
import com.etebase.client.exceptions.ConnectionException
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
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.etebase.EtebaseAttachments.Companion.parse
import org.tasks.etebase.EtebaseClient.Companion.isAttachment
import org.tasks.etebase.EtebaseClient.Companion.isTransient
import org.tasks.files.AttachmentStore
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
    taskAttachmentDao: TaskAttachmentDao,
    attachmentStore: AttachmentStore,
) {
    private val attachments = EtebaseAttachments(taskAttachmentDao, attachmentStore)

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
            fetchChanges(account, client, calendar, collection)
            pushLocalChanges(account, client, calendar, collection)
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
        val dirty = caldavTask != null && dirtyDao.isDirty(caldavTask.id) == true
        iCal.fromVtodo(account, caldavCalendar, caldavTask, remote, vtodo, item.uid, null)
        caldavDao.getTaskByRemoteId(caldavCalendar.uuid!!, remoteId)
            ?.takeIf { !it.isDeleted() }
            ?.let { saved ->
                attachments.applyRemote(saved.task, parse(remote), base?.let(::parse).orEmpty(), dirty) {
                    try {
                        it.uid?.let { uid -> client.fetchContent(collection, uid) }
                            ?.let(EtebaseAttachments::fromItemContent)
                    } catch (e: ServerErrorException) {
                        null // the server cannot give this one (it answers 500 for an unknown uid)
                    }
                }
            }
        return item
    }

    private class Change(
        val item: Item,
        val caldavTask: CaldavTask,
        val vtodo: String? = null,
        val attachments: List<Item> = emptyList(),
        val dirtyVersion: Long? = null,
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
            client.deleteItem(collection, caldavTask)
                ?.let { changes.add(Change(it, caldavTask)) }
                ?: caldavDao.delete(caldavTask)
        }
        for (toPush in dirtyDao.getTasksToPush(caldavCalendar.uuid!!)) {
            val caldavTask = caldavDao.getCaldavTaskById(toPush.caldavTaskId) ?: continue
            try {
                val change = push(account, client, caldavCalendar, collection, toPush.task, caldavTask, toPush.dirtyVersion)
                when {
                    change == null -> Unit
                    change.attachments.any { !it.isDeleted } -> upload(client, collection, caldavCalendar, listOf(change))
                    else -> changes.add(change)
                }
            } catch (e: Exception) {
                if (e.isTransient) throw e
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
            vtodo?.let { fromVtodo(it) }?.let(::parse)?.mapNotNull { it.uid }?.forEach { uid ->
                client.deleteAttachment(collection, uid)?.let(items::add)
            }
            return Change(item, caldavTask, attachments = items)
        }
        val cached = vtodoCache.getVtodo(caldavCalendar, caldavTask)
        val model = cached?.let { fromVtodo(it) } ?: VTodo()
        val moved = if (cached == null) movedFrom(account, client, task) else null
        attachments.applyLocal(
            task = task.id,
            vtodo = model,
            carried = moved?.second.orEmpty(),
            create = { content -> client.createAttachment(collection, content).also(items::add).uid },
            delete = { uid -> client.deleteAttachment(collection, uid)?.let(items::add) },
            copy = { ref ->
                client.fetchContent(moved!!.first, ref.uid!!)?.let { content ->
                    client.createAttachment(collection, content).also(items::add).uid
                }
            },
        )
        val vtodo = iCal.toVtodo(account, caldavTask, task, model)
        val item = client.updateItem(collection, caldavTask, vtodo)
        return Change(item, caldavTask, vtodo.decodeToString(), items, dirtyVersion)
    }

    /**
     * The list a new task of this list was moved from on this device (same account), with the attachments it had
     * there, so that they can be carried over.
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
                change.item.isDeleted ->
                    if (caldavTask.isDeleted()) caldavDao.delete(caldavTask) else taskDeleter.delete(caldavTask.task)
                else -> {
                    vtodoCache.putVtodo(caldavCalendar, caldavTask, change.vtodo)
                    caldavDao.update(caldavTask)
                    change.dirtyVersion?.let { dirtyDao.markPushed(caldavTask.id, it) }
                }
            }
        }
        client.updateCache(collection, items)
    }
}
