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
    private val synchronizer = EtebaseSynchronizer(
        caldavDao, dirtyDao, mock(), TaskDeleter(db.deletionDao(), taskDao, caldavDao, mock(), vtodoCache, mock(), mock()),
        mock { onBlocking { forAccount(any()) } doReturn client },
        iCal, vtodoCache,
        mock { on { reportException(any(), any()) } doAnswer { reported.add(it.getArgument(0)); Unit } },
        db.taskAttachmentDao(), AttachmentStore.None,
    )
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
}
