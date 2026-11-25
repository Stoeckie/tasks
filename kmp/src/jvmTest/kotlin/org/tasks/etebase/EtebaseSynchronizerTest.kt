package org.tasks.etebase

import com.etebase.client.Collection
import com.etebase.client.Item
import com.etebase.client.ItemMetadata
import com.etebase.client.exceptions.ConnectionException
import com.etebase.client.exceptions.EncryptionException
import com.etebase.client.exceptions.MsgPackException
import com.todoroo.astrid.alarms.AlarmCalculator
import com.todoroo.astrid.alarms.AlarmService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.tasks.DatabaseTest
import org.tasks.caldav.FileStorage
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.data.MergedGeofence
import org.tasks.data.TaskSaver
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Place
import org.tasks.data.entity.Task
import org.tasks.files.AttachmentStore
import org.tasks.location.LocationService
import org.tasks.location.MapPosition
import org.tasks.preferences.AppPreferences
import org.tasks.reminders.Random
import org.tasks.service.TaskDeleter
import com.etebase.client.exceptions.HttpException
import com.etebase.client.exceptions.ServerErrorException
import org.junit.Assert.assertNotEquals
import org.mockito.kotlin.eq
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.TaskAttachment
import org.tasks.etebase.EtebaseAttachments.Companion.itemContent
import org.tasks.etebase.EtebaseAttachments.Companion.sha256
import java.io.IOException
import java.nio.file.Files

/** Invariants 1 and 2: nothing received aborts the sync, and one bad item or task never stops the others. */
class EtebaseSynchronizerTest : DatabaseTest() {
    private val caldavDao = db.caldavDao()
    private val taskDao = db.taskDao()
    private val dirtyDao = db.dirtyDao()
    private val vtodoCache = VtodoCache(caldavDao, FileStorage(Files.createTempDirectory("sync").toString()))
    private val preferences: AppPreferences = mock {
        onBlocking { defaultPriority() } doReturn Task.Priority.NONE
        onBlocking { addTasksToTop() } doReturn false
    }
    private val locationService = object : LocationService {
        override val locationDao = db.locationDao()
        override val appPreferences = preferences
        override suspend fun currentLocation(): MapPosition? = null
        override fun addGeofences(geofence: MergedGeofence) = Unit
        override fun removeGeofences(place: Place) = Unit
    }
    private val taskSaver = TaskSaver(taskDao, mock(), mock(), locationService, mock(), mock(), caldavDao)
    private val iCal = iCalendar(
        db.tagDataDao(), preferences, db.locationDao(), mock(), locationService, db.tagDao(), taskDao, dirtyDao,
        taskSaver, caldavDao, db.alarmDao(),
        AlarmService(db.alarmDao(), taskDao, dirtyDao, mock(), mock(), AlarmCalculator(Random()), preferences),
        vtodoCache, mock(),
    )
    private val listMeta: ItemMetadata = mock { on { name } doReturn "list" }
    private val taskMeta: ItemMetadata = mock()
    private val collection: Collection = mock {
        on { uid } doReturn "col"
        on { stoken } doReturn "s1"
        on { meta } doReturn listMeta
    }
    private var page: suspend () -> List<Item> = { emptyList() }
    private val client: EtebaseClient = mock {
        onBlocking { getCollections() } doReturn listOf(collection)
        onBlocking { isCached(any(), any()) } doReturn false
        onBlocking { fetchItems(any(), any(), any()) } doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[2] as suspend (Pair<String?, List<Item>>) -> Unit)("s1" to page())
        }
    }
    private val reported = ArrayList<Throwable>()
    private var store: AttachmentStore = AttachmentStore.None
    private val synchronizer by lazy { EtebaseSynchronizer(
        caldavDao, dirtyDao, mock(), TaskDeleter(db.deletionDao(), taskDao, caldavDao, mock(), vtodoCache, mock(), mock()),
        mock { onBlocking { forAccount(any()) } doReturn client },
        iCal, vtodoCache,
        mock { on { reportException(any(), any()) } doAnswer { reported.add(it.getArgument(0)); Unit } },
        EtebaseAttachments(db.taskAttachmentDao(), store),
    ) }
    private lateinit var account: CaldavAccount

    private fun item(uid: String, content: String? = null, meta: ItemMetadata? = taskMeta): Item = mock {
        if (meta == null) on { this.meta } doThrow MsgPackException("invalid type") else on { this.meta } doReturn meta
        on { this.uid } doReturn uid
        if (content == null) {
            on { contentString } doThrow EncryptionException("Got an invalid chunk index")
        } else {
            on { contentString } doReturn content
        }
    }

    private fun vtodo(uid: String, summary: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$summary\r\n" +
            "DTSTAMP:20261124T100000Z\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    private suspend fun sync(): String {
        synchronizer.sync(account, hasPro = true)
        return caldavDao.getAccountByUuid(account.uuid!!)!!.error.orEmpty()
    }

    private fun setUp() = runBlocking {
        account = CaldavAccount(uuid = "a", accountType = CaldavAccount.TYPE_ETEBASE, password = "x")
            .let { it.copy(id = caldavDao.insert(it)) }
        caldavDao.insert(CaldavCalendar(account = "a", uuid = "cal", url = "col", name = "list"))
    }

    @Test
    fun badItemsAreSkippedAndTheRestIsApplied() = runBlocking {
        setUp()
        page = {
            listOf(
                item("meta", meta = null),
                item("content"),
                item("text", "not iCalendar"),
                item("nouid", vtodo("", "no uid").replace("UID:\r\n", "")),
                item("good", vtodo("good", "good task")),
            )
        }
        assertEquals("", sync())
        // the parser gives a VTODO without UID one of its own
        assertEquals(setOf("no uid", "good task"), taskDao.getAll().map { it.title }.toSet())
        assertEquals("s1", caldavDao.getCalendar("cal")!!.ctag)
        assertEquals(emptyList<Throwable>(), reported)
    }

    @Test
    fun networkErrorEndsTheSyncWithoutSkippingAnything() = runBlocking {
        setUp()
        page = { throw ConnectionException("offline") }
        assertEquals("offline", sync())
        assertNull(caldavDao.getCalendar("cal")!!.ctag)
    }

    @Test
    fun taskThatCannotBePushedDoesNotStopTheOthers() = runBlocking {
        setUp()
        val bad = Task(title = "bad").also { taskDao.createNew(it) }
        val good = Task(title = "good").also { taskDao.createNew(it) }
        listOf(bad, good).forEach { caldavDao.insert(it, CaldavTask(task = it.id, calendar = "cal"), false) }
        dirtyDao.setDirty(listOf(bad.id, good.id))
        val pushed = item("pushed", vtodo("x", "good"))
        client.stub {
            onBlocking { updateItem(any(), any(), any()) } doSuspendableAnswer {
                if (it.getArgument<CaldavTask>(1).task == bad.id) throw EncryptionException("cannot encrypt")
                pushed
            }
        }
        assertEquals("", sync())
        verifyBlocking(client) { uploadChanges(any(), argThat { this == listOf(pushed) }) }
        assertTrue(dirtyDao.isDirty(caldavDao.getTask(bad.id)!!.id) == true)
        assertTrue(dirtyDao.isDirty(caldavDao.getTask(good.id)!!.id) != true)
    }

    private val files = HashMap<String, ByteArray>()

    private fun data(seed: Long) = ByteArray(1000).also { java.util.Random(seed).nextBytes(it) }

    /** An ATTACH reference to item u[id] holding [data], as this build writes it. */
    private fun attach(id: String, data: ByteArray): String {
        files["u$id"] = data
        return "ATTACH;VALUE=URI;FILENAME=$id.bin;SIZE=${data.size};X-TASKS-ID=$id;X-TASKS-SHA256=${sha256(data)}:x-etebase-item:u$id\r\n"
    }

    private fun vtodo(uid: String, summary: String, vararg attach: String) =
        vtodo(uid, summary).replace("END:VTODO", attach.joinToString("") + "END:VTODO")

    private suspend fun linkFile(task: Long, id: String, data: ByteArray) {
        val uri = "mem:///local/$id"
        (store as org.tasks.etebase.sim.MemStore).files[uri] = data
        db.taskAttachmentDao().insert(TaskAttachment(remoteId = id, name = "$id.bin", uri = uri))
        val file = db.taskAttachmentDao().getAttachment(id)!!
        db.taskAttachmentDao().insert(listOf(Attachment(task = task, fileId = file.id!!, attachmentUid = id)))
    }

    private suspend fun linked(task: Long) = db.taskAttachmentDao().getAttachments(task).map { it.remoteId }.toSet()

    /** A task "t" in [calendar] that this device has synced with [vtodo]. */
    private suspend fun syncedTask(vtodo: String, calendar: String = "cal"): Task {
        val task = Task(title = "t").also { taskDao.createNew(it) }
        val caldavTask = CaldavTask(task = task.id, calendar = calendar, remoteId = "t", obj = "t-item")
        val id = caldavDao.insert(task, caldavTask, false)
        vtodoCache.putVtodo(caldavDao.getCalendar(calendar)!!, caldavTask.copy(id = id), vtodo)
        dirtyDao.markSynced(id)
        return task
    }

    /** REVIEW4 F2: a download that is cut off leaves the base as it was, so a removal on another device still arrives. */
    @Test
    fun interruptedDownloadKeepsTheBase() = runBlocking {
        setUp()
        store = org.tasks.etebase.sim.MemStore()
        val f = data(1); val g = data(2); val h = data(3)
        val task = syncedTask(vtodo("t", "t", attach("F", f), attach("G", g)))
        linkFile(task.id, "F", f)
        linkFile(task.id, "G", g)
        // another device removed G and added H
        page = { listOf(item("t-item", vtodo("t", "t", attach("F", f), attach("H", h)))) }
        var offline = true
        client.stub {
            onBlocking { isSeen(any(), any()) } doReturn true
            onBlocking { fetchContent(any(), any()) } doSuspendableAnswer {
                if (offline) throw IOException("connection reset during the download")
                files[it.getArgument<String>(1)]?.let(::itemContent)
            }
        }
        assertNotEquals("", sync())
        offline = false
        assertEquals("", sync())
        assertEquals(setOf("F", "H"), linked(task.id))
    }

    /** REVIEW4 F4: an upload the server refuses (413 from a proxy) does not stop the other lists. */
    @Test
    fun refusedUploadDoesNotStopTheOtherLists() = runBlocking {
        setUp()
        store = org.tasks.etebase.sim.MemStore()
        val collection2: Collection = mock {
            on { uid } doReturn "col2"
            on { stoken } doReturn "s1"
            on { meta } doReturn listMeta
        }
        caldavDao.insert(CaldavCalendar(account = "a", uuid = "cal2", url = "col2", name = "list"))
        val big = Task(title = "big").also { taskDao.createNew(it) }
        caldavDao.insert(big, CaldavTask(task = big.id, calendar = "cal"), false)
        linkFile(big.id, "B", data(4))
        val other = Task(title = "other").also { taskDao.createNew(it) }
        caldavDao.insert(other, CaldavTask(task = other.id, calendar = "cal2"), false)
        dirtyDao.setDirty(listOf(big.id, other.id))
        val pushed = item("pushed", vtodo("x", "other"))
        val attachment = item("attachment", "")
        client.stub {
            onBlocking { getCollections() } doReturn listOf(collection, collection2)
            onBlocking { createAttachment(any(), any()) } doReturn attachment
            onBlocking { updateItem(any(), any(), any()) } doReturn pushed
            onBlocking { uploadChanges(eq(collection), any()) } doSuspendableAnswer {
                throw HttpException("413 Request Entity Too Large")
            }
        }
        assertEquals("", sync())
        verifyBlocking(client) { uploadChanges(eq(collection2), any()) }
        assertTrue(dirtyDao.isDirty(caldavDao.getTask(big.id)!!.id) == true)
        // the proxy accepts it now: the task is pushed with its attachment
        client.stub { onBlocking { uploadChanges(eq(collection), any()) } doReturn Unit }
        assertEquals("", sync())
        verifyBlocking(client, org.mockito.kotlin.times(2)) { uploadChanges(eq(collection), argThat { this == listOf(attachment, pushed) }) }
        assertTrue(dirtyDao.isDirty(caldavDao.getTask(big.id)!!.id) != true)
    }

    /**
     * REVIEW4 F5, REVIEW5 F7: a server error during a download leaves the page unconfirmed, so the next sync lists the
     * task again and tries again (without a retry timer); the old base stays until then.
     */
    @Test
    fun downloadThatTheServerCannotAnswerIsTriedAgainByTheNextSync() = runBlocking {
        setUp()
        store = org.tasks.etebase.sim.MemStore()
        val h = data(3)
        page = { listOf(item("t-item", vtodo("t", "t", attach("H", h)))) }
        var down = true
        client.stub {
            onBlocking { isSeen(any(), any()) } doReturn true
            onBlocking { fetchContent(any(), any()) } doSuspendableAnswer {
                if (down) throw ServerErrorException("500")
                files[it.getArgument<String>(1)]?.let(::itemContent)
            }
        }
        assertEquals("", sync())
        val task = taskDao.getAll().single()
        assertEquals(emptySet<String>(), linked(task.id))
        assertNull(caldavDao.getCalendar("cal")!!.ctag) // listed again next time
        down = false
        assertEquals("", sync())
        assertEquals(setOf("H"), linked(task.id))
        assertEquals(h.toList(), (store as org.tasks.etebase.sim.MemStore).files[db.taskAttachmentDao().getAttachment("H")!!.uri]!!.toList())
        assertEquals("s1", caldavDao.getCalendar("cal")!!.ctag)
    }
}
