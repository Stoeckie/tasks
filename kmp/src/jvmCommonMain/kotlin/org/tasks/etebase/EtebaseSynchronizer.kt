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
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_ETEBASE
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.etebase.EtebaseAttachments.Companion.parse
import org.tasks.etebase.EtebaseClient.Companion.isAttachment
import org.tasks.etebase.EtebaseClient.Companion.endsSync
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
        // all lists are fetched before any is pushed: a move carries from its old list as it is now, and a move made
        // elsewhere is followed to where the task is now
        val moves = Moves()
        val fetched = collections.mapNotNull { collection ->
            perList(collection) {
                val calendar = calendar(account, collection)
                // a list fetched only in part is not pushed: its cache may lack items this device pushed itself
                calendar.takeIf { fetchChanges(account, client, calendar, collection, moves) }?.let { it to collection }
            }
        }
        followMoves(account, client, moves)
        for ((calendar, collection) in fetched) {
            perList(collection) { pushLocalChanges(account, client, calendar, collection, moves.adopted) }
        }
    }

    /** One list never stops the sync of the others. */
    private suspend fun <T> perList(collection: Collection, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            if (e.endsSync) throw e
            Logger.e("EtebaseSynchronizer", e) { "Not synchronized: ${collection.uid}" }
            null
        }

    private suspend fun calendar(account: CaldavAccount, collection: Collection): CaldavCalendar {
        var calendar = caldavDao.getCalendarByUrl(account.uuid!!, collection.uid)
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
        return calendar
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

    /** Returns false when the server could not give some item now (it is listed again next time). */
    private suspend fun fetchChanges(
        account: CaldavAccount,
        client: EtebaseClient,
        caldavCalendar: CaldavCalendar,
        collection: Collection,
        moves: Moves,
    ): Boolean {
        if (caldavCalendar.ctag?.equals(collection.stoken) == true) {
            Logger.d("EtebaseSynchronizer") { "${caldavCalendar.name} up to date" }
            return true
        }
        Logger.d("EtebaseSynchronizer") { "updating $caldavCalendar" }
        var complete = true
        var hold = false
        client.fetchItems(collection, caldavCalendar) { (stoken, items) ->
            val pending = moves.pending.size + moves.alsoMovedHere.size
            // attachment items are cached as listed (without content) first: task items of this page reference them
            client.updateCache(collection, items.filter { it.isAttachment })
            val applied = items.filter { !it.isAttachment }.mapNotNull { item ->
                try {
                    applyRemote(account, client, collection, caldavCalendar, item, moves)
                } catch (e: Exception) {
                    if (e.endsSync) throw e
                    if (e is ServerErrorException || e is ChangedMeanwhile) {
                        // the server could not give it (or an attachment of it) now: listed again by the next sync
                        complete = false
                        Logger.e("EtebaseSynchronizer", e) { "Not fetched: item ${item.uid}" }
                        return@mapNotNull null
                    }
                    // one bad item never stops the sync of the others
                    Logger.e("EtebaseSynchronizer", e) { "Skipped item ${item.uid}" }
                    item
                }
            }
            client.updateCache(collection, applied)
            // a move followed only at the end of this sync is listed again if the app stops before that
            hold = hold || moves.pending.size + moves.alsoMovedHere.size > pending
            if (complete && !hold) {
                stoken?.let {
                    caldavCalendar.ctag = it
                    caldavDao.update(caldavCalendar)
                }
            }
        }
        Logger.d("EtebaseSynchronizer") { "UPDATE $caldavCalendar" }
        caldavDao.update(caldavCalendar)
        Logger.d("EtebaseSynchronizer") { "Updating parents for ${caldavCalendar.uuid}" }
        caldavDao.updateParents(caldavCalendar.uuid!!)
        refreshBroadcaster.broadcastRefresh()
        return complete
    }

    /** Applies a listed task [listed] (without content); returns the item to cache, or null if it is not cached now. */
    private suspend fun applyRemote(
        account: CaldavAccount,
        client: EtebaseClient,
        collection: Collection,
        caldavCalendar: CaldavCalendar,
        listed: Item,
        moves: Moves,
    ): Item? {
        if (client.isCached(collection, listed)) return null // this device has it (also its own pushes)
        val item = if (listed.isMissingContent) client.fetch(collection, listed.uid) else listed
        // changed since it was listed: applied when that revision is listed (with the attachment items before it);
        // until then this list is not pushed (its cache lacks the item)
        if (item.etag != listed.etag) throw ChangedMeanwhile()
        val vtodo = item.contentString
        val remote = fromVtodo(vtodo) ?: return item
        val remoteId = remote.uid ?: return item
        val caldavTask = caldavDao.getTaskByRemoteId(caldavCalendar.uuid!!, remoteId)
        if (caldavTask?.isDeleted() == true) {
            // the old item of a task moved from here on this device: what was removed there meanwhile goes too (also
            // when it was deleted since: a deleted revision keeps its last content)
            val before = client.cachedVtodo(collection, item.uid)?.let { fromVtodo(it) }?.let(::parse).orEmpty()
            if (attachments.removedElsewhere(caldavTask.task, before, parse(remote))) {
                dirtyDao.setDirty(listOf(caldavTask.task), listOf(TYPE_ETEBASE))
            }
        }
        if (item.isDeleted) {
            val movedTo = item.meta.description?.takeIf { it.startsWith(MOVED) }?.removePrefix(MOVED)
                ?.split('/', limit = 2)?.takeIf { it.size == 2 }?.let { (url, uid) -> url to uid }
            movedTo?.let { moves.to[remoteId] = it } ?: moves.deleted.add(remoteId)
            when {
                caldavTask == null -> Unit
                // moved here too; if the other device was first, this device may follow its move (at the end of the
                // fetch, the old row goes then too)
                caldavTask.isDeleted() && movedTo != null -> {
                    val base = client.cachedVtodo(collection, item.uid)?.let { fromVtodo(it) }?.let(::parse).orEmpty()
                    moves.alsoMovedHere.add(Triple(caldavTask, movedTo, base))
                    return null // cached once followed: until then its last content is the base
                }
                caldavTask.isDeleted() -> caldavDao.delete(caldavTask)
                movedTo != null && (changedHere(caldavCalendar, caldavTask) || hasMovedRows(caldavTask)) -> {
                    moves.pending.add(caldavTask to movedTo)
                    return null
                }
                else -> deletedElsewhere(caldavTask)
            }
            return item
        }
        caldavTask?.obj = item.uid
        val base = caldavTask?.let { vtodoCache.getVtodo(caldavCalendar, it) }?.let { fromVtodo(it) }
        iCal.fromVtodo(account, caldavCalendar, caldavTask, remote, vtodo, item.uid, null, cacheVtodo = false)
        caldavDao.getTaskByRemoteId(caldavCalendar.uuid!!, remoteId)
            ?.takeIf { !it.isDeleted() }
            ?.let { saved ->
                attachments.applyRemote(saved.task, parse(remote), base?.let(::parse).orEmpty(), hasMovedRows(saved)) { ref ->
                    download(client, collection, ref)
                }
                // the new base only once the attachments are applied: a download that is cut off leaves the old one
                vtodoCache.putVtodo(caldavCalendar, saved, vtodo)
            }
        return item
    }

    private class ChangedMeanwhile : Exception()

    /** Moves made by other devices, as this sync finds them in the deleted old items (which name the new item). */
    private class Moves {
        /** UID of a deleted item to the list and UID of the item that replaced it. */
        val to = HashMap<String, Pair<String, String>>()
        /** UIDs of items deleted without a move. */
        val deleted = HashSet<String>()
        /** Tasks with attachment changes not yet pushed, whose item was deleted by such a move. */
        val pending = ArrayList<Pair<CaldavTask, Pair<String, String>>>()
        /** Old rows of tasks that this device moved too (with where the other device moved it, references of the old item). */
        val alsoMovedHere = ArrayList<Triple<CaldavTask, Pair<String, String>, List<AttachmentRef>>>()
        /** Rows made here for the new item of such a move: pushed once that item was fetched (else it would be there twice). */
        val adopted = HashSet<Long>()
    }

    /** Whether attachments of the task of [caldavTask] were added or removed here and not pushed yet. */
    private suspend fun changedHere(calendar: CaldavCalendar, caldavTask: CaldavTask): Boolean {
        if (taskDeleter.isDeleted(caldavTask.task)) return false
        // no base: its first pull did not complete (a pull links nothing then), so every file it has was added here
        val base = vtodoCache.getVtodo(calendar, caldavTask)?.let { fromVtodo(it) }?.let(::parse).orEmpty()
        return attachments.changedHere(caldavTask.task, base)
    }

    /**
     * Tasks that another device moved to another list (also more than once) while their attachments changed here: the
     * changes are not lost, they go to the task where it is now; if that has not arrived here, this task goes there and
     * is merged with it when it does.
     */
    private suspend fun followMoves(account: CaldavAccount, client: EtebaseClient, moves: Moves) {
        // the same task moved here and on another device: while this device has not pushed its copy, it follows the
        // other move (only what was changed here goes along), so that the task is not there twice
        for ((old, first, base) in moves.alsoMovedHere) {
            caldavDao.delete(old)
            val task = old.task
            val rows = caldavDao.getTasks(task)
            if (rows.none { !it.isDeleted() } || rows.any { pushed(account, client, it) }) continue // two copies
            val there = follow(account, moves, first).second ?: continue
            if (there.task == task || taskDeleter.isDeleted(there.task)) continue
            attachments.transfer(task, there.task, base)
            dirtyDao.setDirty(listOf(there.task), listOf(TYPE_ETEBASE))
            taskDeleter.delete(task)
            attachments.forget(task)
        }
        for ((caldavTask, first) in moves.pending) {
            val (target, there) = follow(account, moves, first)
            val calendar = caldavDao.getCalendarByUrl(account.uuid!!, target.first)
            val base = caldavDao.getCalendar(caldavTask.calendar!!)?.let { vtodoCache.getVtodo(it, caldavTask) }
                ?.let { fromVtodo(it) }?.let(::parse).orEmpty()
            when {
                there != null -> {
                    // (also when this device moved that task on meanwhile: links belong to the task, not to its row)
                    if (there.task != caldavTask.task && !taskDeleter.isDeleted(there.task)) {
                        attachments.transfer(caldavTask.task, there.task, base)
                        // a move of this device that is not done yet goes on from there: its old item is carried,
                        // except what this device's copy had and the task lacks there (removed on the other device)
                        val old = caldavDao.getTasks(caldavTask.task).filter { it.isDeleted() }
                        if (old.isNotEmpty()) {
                            val now = caldavDao.getCalendar(there.calendar!!)?.let { vtodoCache.getVtodo(it, there) }
                                ?.let { fromVtodo(it) }?.let(::parse).orEmpty()
                            attachments.removedElsewhere(there.task, base, now)
                        }
                        old.forEach { caldavDao.update(it.copy(task = there.task)) }
                        dirtyDao.setDirty(listOf(there.task), listOf(TYPE_ETEBASE))
                    }
                    deletedElsewhere(caldavTask)
                }
                calendar == null || target.second in moves.deleted -> deletedElsewhere(caldavTask)
                else -> {
                    moves.adopted.add(caldavDao.insert(CaldavTask(task = caldavTask.task, calendar = calendar.uuid, remoteId = target.second)))
                    caldavDao.delete(caldavTask)
                    dirtyDao.setDirty(listOf(caldavTask.task), listOf(TYPE_ETEBASE))
                }
            }
        }
    }

    /** Whether the item of [row] (in a list of [account]) is on the server: this device has it in its cache. */
    private suspend fun pushed(account: CaldavAccount, client: EtebaseClient, row: CaldavTask): Boolean {
        val collection = caldavDao.getCalendar(row.calendar!!)?.takeIf { it.account == account.uuid }
            ?.let { client.collection(it) } ?: return false
        return row.obj?.let { client.cachedVtodo(collection, it) } != null
    }

    /** Where a moved task is now (following moves of moves), and its row here if it has arrived. */
    private suspend fun follow(account: CaldavAccount, moves: Moves, first: Pair<String, String>): Pair<Pair<String, String>, CaldavTask?> {
        var target = first
        var there = row(account, target)
        val followed = HashSet<String>()
        while (there == null && followed.add(target.second)) {
            target = moves.to[target.second] ?: break
            there = row(account, target)
        }
        return target to there
    }

    private suspend fun row(account: CaldavAccount, target: Pair<String, String>): CaldavTask? =
        caldavDao.getCalendarByUrl(account.uuid!!, target.first)?.let { caldavDao.getTaskByRemoteId(it.uuid!!, target.second) }

    private suspend fun hasMovedRows(caldavTask: CaldavTask) = caldavDao.getTasks(caldavTask.task).any { it.isDeleted() }

    /** The item of [caldavTask] was deleted on another device: so is the task here. */
    private suspend fun deletedElsewhere(caldavTask: CaldavTask) {
        if (hasMovedRows(caldavTask)) {
            // moved here before: the old item is deleted by the push of its list, as for a deleted task
            caldavDao.delete(caldavTask)
            taskDeleter.markDeleted(listOf(caldavTask.task))
        } else {
            taskDeleter.delete(caldavTask.task)
            attachments.forget(caldavTask.task)
        }
    }

    /**
     * The file of [ref] from [collection], or null when it is not there (never listed here, deleted, unreadable). A
     * server error is thrown: the item of the task is then listed and applied again by the next sync.
     */
    private suspend fun download(client: EtebaseClient, collection: Collection, ref: AttachmentRef): ByteArray? =
        if (client.isSeen(collection, ref.uid)) {
            client.fetchContent(collection, ref.uid)?.let(EtebaseAttachments::fromItemContent)
        } else {
            null
        }

    private class Change(
        val item: Item,
        val caldavTask: CaldavTask,
        val vtodo: String? = null,
        val attachments: List<Item> = emptyList(),
        val dirtyVersion: Long? = null,
        val pushed: List<String> = emptyList(),
    ) {
        val uploads get() = attachments.any { !it.isDeleted }
    }

    private suspend fun pushLocalChanges(
        account: CaldavAccount,
        client: EtebaseClient,
        caldavCalendar: CaldavCalendar,
        collection: Collection,
        later: Set<Long>,
    ) {
        val changes = ArrayList<Change>()
        for (caldavTask in caldavDao.getMoved(caldavCalendar.uuid!!)) {
            try {
                movedAway(account, client, collection, caldavTask)?.let(changes::add)
            } catch (e: Exception) {
                if (e.endsSync) throw e
                Logger.e("EtebaseSynchronizer", e) { "Old item kept: task ${caldavTask.task}" }
            }
        }
        for (toPush in dirtyDao.getTasksToPush(caldavCalendar.uuid!!)) {
            if (toPush.caldavTaskId in later) continue
            val caldavTask = caldavDao.getCaldavTaskById(toPush.caldavTaskId) ?: continue
            try {
                val change = push(account, client, collection, caldavCalendar, toPush.task, caldavTask, toPush.dirtyVersion)
                when {
                    change == null -> Unit
                    // a task with new attachment items on its own: a large upload that fails stops nothing else
                    change.uploads -> upload(client, collection, caldavCalendar, listOf(change))
                    else -> changes.add(change)
                }
            } catch (e: Exception) {
                if (e.endsSync) throw e
                // one task never stops the push of the others; it stays dirty (also after a 413 from a proxy)
                Logger.e("EtebaseSynchronizer", e) { "Not pushed: task ${toPush.task.id}" }
            }
        }
        upload(client, collection, caldavCalendar, changes)
    }

    private suspend fun push(
        account: CaldavAccount,
        client: EtebaseClient,
        collection: Collection,
        caldavCalendar: CaldavCalendar,
        task: Task,
        caldavTask: CaldavTask,
        dirtyVersion: Long,
    ): Change? {
        if (task.isDeleted) {
            // the references of the revision this device has; nothing on the server is asked for
            val refs = caldavTask.obj?.let { client.cachedVtodo(collection, it) }?.let { fromVtodo(it) }?.let(::parse)
            val item = client.deleteItem(collection, caldavTask)
                ?: run {
                    forget(caldavTask) // never reached the server
                    return null
                }
            return Change(item, caldavTask, attachments = refs.orEmpty().mapNotNull { client.deleteAttachment(collection, it.uid) })
        }
        val model = vtodoCache.getVtodo(caldavCalendar, caldavTask)?.let { fromVtodo(it) } ?: VTodo()
        val items = ArrayList<Item>()
        // until the old items of a moved task are gone, what they reference is carried (also what was added there)
        val old = oldItems(account, client, task.id)
        val carried = old.flatMap { (oldCollection, refs) ->
            attachments.toCarry(task.id, refs, parse(model)) { client.isSeen(oldCollection, it.uid) }
                .map { oldCollection to it }
        }
        attachments.applyLocal(
            task = task.id,
            vtodo = model,
            carried = carried.map { it.second },
            create = { content -> client.createAttachment(collection, content).also(items::add).uid },
            delete = { uid -> client.deleteAttachment(collection, uid)?.let(items::add) },
            copy = { ref -> carried.first { it.second == ref }.first.let { client.fetchContent(it, ref.uid) } },
        )
        val vtodo = iCal.toVtodo(account, caldavTask, task, model)
        val item = client.updateItem(collection, caldavTask, vtodo)
        return Change(item, caldavTask, vtodo.decodeToString(), items, dirtyVersion, parse(model).map { it.id })
    }

    /**
     * The deletion of the old item of a task that was moved from this list, once the task is in its new list, with
     * the attachment items that the new list has copies of (or that the user removed). If the old item references an
     * attachment that the new one lacks (added on another device during the move), the task is pushed to the new list
     * again first, and the old item stays until then.
     */
    private suspend fun movedAway(
        account: CaldavAccount,
        client: EtebaseClient,
        collection: Collection,
        caldavTask: CaldavTask,
    ): Change? {
        val task = caldavTask.task
        val rows = caldavDao.getTasks(task).filter { !it.isDeleted() && it.id != caldavTask.id }
        val deleted = taskDeleter.isDeleted(task)
        if (!deleted && rows.any { dirtyDao.isDirty(it.id) == true && sameAccount(account, it) }) {
            return null // the old item stays until the new list has the task
        }
        val refs = caldavTask.obj?.let { client.cachedVtodo(collection, it) }?.let { fromVtodo(it) }?.let(::parse).orEmpty()
        val item = client.deleteItem(collection, caldavTask)
            ?: run {
                forget(caldavTask) // nothing on the server
                return null
            }
        val obsolete = when {
            deleted -> refs // deleted after the move
            else -> {
                val (url, row, new) = movedTo(account, rows) ?: return Change(item, caldavTask) // another account: the old items stay
                if (attachments.toCarry(task, refs, new) { client.isSeen(collection, it.uid) }.isNotEmpty()) {
                    dirtyDao.setDirty(listOf(task), listOf(TYPE_ETEBASE))
                    return null
                }
                // devices that changed attachments of the task meanwhile find where it went
                item.meta = item.meta.apply { description = "$MOVED$url/${row.remoteId}" }
                refs.filter { ref -> new.none { it.id == ref.id && it.uid == ref.uid } }
            }
        }
        return Change(item, caldavTask, attachments = obsolete.mapNotNull { client.deleteAttachment(collection, it.uid) })
    }

    private suspend fun sameAccount(account: CaldavAccount, row: CaldavTask) =
        caldavDao.getCalendar(row.calendar!!)?.account == account.uuid

    /** Where the task is in another list of [account] (one of [rows]): list, row, references; null if not there. */
    private suspend fun movedTo(account: CaldavAccount, rows: List<CaldavTask>): Triple<String, CaldavTask, List<AttachmentRef>>? {
        for (row in rows) {
            val calendar = caldavDao.getCalendar(row.calendar!!)?.takeIf { it.account == account.uuid } ?: continue
            return Triple(calendar.url!!, row, vtodoCache.getVtodo(calendar, row)?.let { fromVtodo(it) }?.let(::parse).orEmpty())
        }
        return null
    }

    /** The old items of [task], moved on this device from lists of [account]: their collections and references. */
    private suspend fun oldItems(
        account: CaldavAccount,
        client: EtebaseClient,
        task: Long,
    ): List<Pair<Collection, List<AttachmentRef>>> =
        caldavDao.getTasks(task).filter { it.isDeleted() && it.obj != null }.mapNotNull { old ->
            val calendar = caldavDao.getCalendar(old.calendar!!)?.takeIf { it.account == account.uuid }
            val collection = calendar?.let { client.collection(it) } ?: return@mapNotNull null
            val vtodo = client.cachedVtodo(collection, old.obj!!) ?: return@mapNotNull null
            collection to fromVtodo(vtodo)?.let(::parse).orEmpty()
        }

    /** [caldavTask] is gone from its list; the task too, once it is deleted and in no list any more. */
    private suspend fun forget(caldavTask: CaldavTask) {
        caldavDao.delete(caldavTask)
        if (caldavDao.getTasks(caldavTask.task).isEmpty() && taskDeleter.isDeleted(caldavTask.task)) {
            taskDeleter.delete(caldavTask.task)
            attachments.forget(caldavTask.task)
        }
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
            if (change.item.isDeleted) {
                forget(caldavTask)
            } else {
                vtodoCache.putVtodo(caldavCalendar, caldavTask, change.vtodo)
                // the row as it is now: the user may have moved the task during the upload
                caldavDao.getCaldavTaskById(caldavTask.id)?.let { caldavDao.update(it.copy(obj = caldavTask.obj)) }
                change.dirtyVersion?.let { dirtyDao.markPushed(caldavTask.id, it) }
                attachments.pushed(
                    task = caldavTask.task,
                    pushed = change.pushed,
                    moved = caldavDao.getTasks(caldavTask.task).any { it.isDeleted() },
                )
            }
        }
        // new attachment items are not cached: this device has their files (it lists them later, without content)
        client.updateCache(collection, changes.map { it.item } + changes.flatMap { it.attachments }.filter { it.isDeleted })
    }

    companion object {
        /** Description of the deleted old item of a moved task: `moved:<list>/<UID in that list>`. */
        private const val MOVED = "moved:"
    }
}
