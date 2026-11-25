package org.tasks.etebase

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.etebase.client.Account
import com.etebase.client.FetchOptions
import com.etebase.client.Item
import com.etebase.client.ItemMetadata
import com.etebase.client.PrefetchOption
import com.todoroo.astrid.alarms.AlarmCalculator
import com.todoroo.astrid.alarms.AlarmService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.tasks.analytics.Reporting
import org.tasks.caldav.FileStorage
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.data.MergedGeofence
import org.tasks.data.TaskSaver
import org.tasks.data.db.Database
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Place
import org.tasks.data.entity.Task
import org.tasks.data.entity.TaskAttachment
import org.tasks.files.AttachmentStore
import org.tasks.location.LocationService
import org.tasks.location.MapPosition
import org.tasks.preferences.AppPreferences
import org.tasks.reminders.Random
import org.tasks.service.TaskDeleter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * Devices with the real EtebaseSynchronizer, EtebaseClient, patched etebase library, iCalendar, TaskDeleter and Room
 * against an Etebase test server, counting HTTP bytes. Opt-in:
 *
 * ```
 * ETEBASE_TEST_SERVER=http://127.0.0.1:3735 ETEBASE_TEST_USER=test1 ETEBASE_TEST_CREDENTIALS=/path/creds.json \
 *   [ETEBASE_TEST_PYTHON=/path/to/python-with-etebase] ./gradlew :kmp:jvmTest --tests "*EtebaseLiveTest*"
 * ```
 * creds.json maps user names to passwords. Every test works in new collections and deletes them afterwards.
 * ETEBASE_TEST_PYTHON (a Python with the unpatched etebase package) adds an item written by an affected library.
 */
class EtebaseLiveTest {
    private val url: String? = System.getenv("ETEBASE_TEST_SERVER")
    private val user = System.getenv("ETEBASE_TEST_USER") ?: "test1"
    private val credentials = System.getenv("ETEBASE_TEST_CREDENTIALS")
    private val log: File? = System.getenv("ETEBASE_TEST_LOG")?.let { File(it) }

    class MemStore : AttachmentStore {
        val files = linkedMapOf<String, ByteArray>()
        override suspend fun size(uri: String) = files[uri]?.size?.toLong()
        override suspend fun read(uri: String, maxBytes: Int) = files[uri]?.takeIf { it.size <= maxBytes }
        override suspend fun mimeType(uri: String) = "application/octet-stream"
        override suspend fun create(name: String, mimeType: String?, data: ByteArray) =
            "mem:///${files.size}/$name".also { files[it] = data }
    }

    class Result(val up: Long, val down: Long, val requests: Long)

    inner class Device(val name: String, val store: AttachmentStore = MemStore()) {
        private val up = AtomicLong()
        private val down = AtomicLong()
        private val requests = AtomicLong()
        @Volatile var loseNextUploadReply = false
        private val dir: File = Files.createTempDirectory("live-$name").toFile()
        val db: Database = Room.inMemoryDatabaseBuilder<Database>()
            .setDriver(BundledSQLiteDriver())
            .addCallback(Database.CALLBACK)
            .build()
        val caldavDao = db.caldavDao()
        val taskDao = db.taskDao()
        val dirtyDao = db.dirtyDao()
        val attachmentDao = db.taskAttachmentDao()
        private val vtodoCache = VtodoCache(caldavDao, FileStorage(dir.absolutePath))
        private val appPreferences: AppPreferences = mock {
            onBlocking { addTasksToTop() } doReturn false
            onBlocking { isDefaultDueTimeEnabled() } doReturn false
            onBlocking { defaultAlarms() } doReturn emptyList()
            onBlocking { defaultDueTime() } doReturn 0
            onBlocking { isCurrentlyQuietHours() } doReturn false
            onBlocking { defaultPriority() } doReturn Task.Priority.NONE
            onBlocking { defaultRingMode() } doReturn 0
            onBlocking { defaultLocationReminder() } doReturn 0
        }
        private val locationService = object : LocationService {
            override val locationDao = db.locationDao()
            override val appPreferences = this@Device.appPreferences
            override suspend fun currentLocation(): MapPosition? = null
            override fun addGeofences(geofence: MergedGeofence) = Unit
            override fun removeGeofences(place: Place) = Unit
        }
        private val taskSaver = TaskSaver(
            taskDao = taskDao, refreshBroadcaster = mock(), notifier = mock(), locationService = locationService,
            timerPlugin = mock(), backgroundWork = mock(), caldavDao = caldavDao,
        )
        private val iCal = iCalendar(
            tagDataDao = db.tagDataDao(), preferences = appPreferences, locationDao = db.locationDao(),
            geocoder = mock(), locationService = locationService, tagDao = db.tagDao(), taskDao = taskDao,
            dirtyDao = dirtyDao, taskSaver = taskSaver, caldavDao = caldavDao, alarmDao = db.alarmDao(),
            alarmService = AlarmService(
                alarmDao = db.alarmDao(), taskDao = taskDao, dirtyDao = dirtyDao, refreshBroadcaster = mock(),
                notifier = mock(), alarmCalculator = AlarmCalculator(Random()), preferences = appPreferences,
            ),
            vtodoCache = vtodoCache, notifier = mock(),
        )
        val taskDeleter = TaskDeleter(db.deletionDao(), taskDao, caldavDao, mock(), vtodoCache, mock(), mock())
        private val okHttp: OkHttpClient = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            requests.incrementAndGet()
            up.addAndGet(request.body?.contentLength()?.coerceAtLeast(0) ?: 0)
            val response = chain.proceed(request)
            if (loseNextUploadReply && request.url.encodedPath.contains("/transaction/")) {
                loseNextUploadReply = false
                response.close()
                throw java.io.IOException("test: reply lost after the server applied the upload")
            }
            response.newBuilder().body(counting(response.body!!, down)).build()
        }.build()
        val etebase: Account = Account.login(OkHttpBridge.createClient(okHttp, url!!), user, password())
        private val client = spy(EtebaseClient(dir.absolutePath, "$user-$name", etebase, caldavDao))
        var account = CaldavAccount(
            uuid = "acc-$name", accountType = CaldavAccount.TYPE_ETEBASE, url = url, username = user, password = "x",
        )
        val exceptions = ArrayList<Throwable>()

        init {
            runBlocking {
                account = account.copy(id = caldavDao.insert(account))
                // only the collections of this test, in the order they were made
                val cache = EtebaseClient::class.java.getDeclaredField("cache")
                    .apply { isAccessible = true }
                    .get(client) as EtebaseLocalCache
                whenever(client.getCollections()) doSuspendableAnswer {
                    val manager = etebase.collectionManager
                    collectionUids.map { manager.fetch(it, null) }
                        .filterNot { it.isDeleted }
                        .onEach { cache.collectionSet(manager, it) }
                }
            }
        }

        private val synchronizer = EtebaseSynchronizer(
            caldavDao, dirtyDao, mock(), taskDeleter,
            mock<EtebaseClientProvider> { onBlocking { forAccount(any()) } doReturn client },
            iCal, vtodoCache,
            object : Reporting {
                override fun logEvent(event: String, vararg params: Pair<String, Any>) = Unit
                override fun addTask(source: String) = Unit
                override fun completeTask(source: String) = Unit
                override fun setSku(sku: String?) = Unit
                override val tasksPreferences: org.tasks.preferences.TasksPreferences get() = mock()
                override val analyticsEnabled = false
                override fun reportException(t: Throwable, fatal: Boolean) {
                    exceptions.add(t)
                }
            },
            attachmentDao,
            store,
        )

        /** Syncs; [expectError] for a sync whose upload reply is lost on purpose. */
        suspend fun sync(label: String, expectError: Boolean = false): Result {
            up.set(0); down.set(0); requests.set(0)
            synchronizer.sync(account, hasPro = true)
            val error = caldavDao.getAccountByUuid(account.uuid!!)!!.error.orEmpty()
            val result = Result(up.get(), down.get(), requests.get())
            note("$label: $name up=${result.up} down=${result.down} requests=${result.requests} error=$error")
            assertEquals("sync error on $name in $label", expectError, error.isNotEmpty())
            assertEquals("exceptions on $name in $label", emptyList<Throwable>(), exceptions)
            return result
        }

        suspend fun calendar(uid: String) = caldavDao.getCalendarByUrl(account.uuid!!, uid)!!

        suspend fun newTask(calendarUid: String, title: String): Task {
            val task = Task(title = title)
            taskDao.createNew(task)
            caldavDao.insert(task, CaldavTask(task = task.id, calendar = calendar(calendarUid).uuid), false)
            dirtyDao.setDirty(listOf(task.id))
            return task
        }

        suspend fun attach(title: String, id: String, bytes: ByteArray) {
            val uri = "mem:///local/$id"
            (store as MemStore).files[uri] = bytes
            attachmentDao.insert(TaskAttachment(remoteId = id, name = "$id.bin", uri = uri))
            val file = attachmentDao.getAttachment(id)!!
            val task = task(title).id
            attachmentDao.insert(listOf(Attachment(task = task, fileId = file.id!!, attachmentUid = id)))
            dirtyDao.setDirty(listOf(task))
        }

        suspend fun remove(title: String, id: String) {
            val task = task(title).id
            attachmentDao.delete(task, id)
            dirtyDao.setDirty(listOf(task))
        }

        suspend fun task(title: String) =
            taskDao.fetch(taskDao.getAll().map { it.id }).single { it.title == title && !it.isDeleted }

        suspend fun edit(title: String, block: Task.() -> Unit) {
            val task = task(title)
            val original = task.copy()
            task.block()
            taskSaver.save(task, original)
        }

        suspend fun content(title: String) = attachmentDao.getAttachments(task(title).id)
            .map { (store as MemStore).files[it.uri]!! }

        suspend fun move(title: String, toCollection: String) {
            val task = task(title)
            taskDeleter.markMoved(listOf(task.id))
            caldavDao.insert(task, CaldavTask(task = task.id, calendar = calendar(toCollection).uuid), false)
            dirtyDao.setDirty(listOf(task.id))
        }
    }

    private val collectionUids = mutableListOf<String>()
    private val devices = ArrayList<Device>()
    private val severity = Logger.config.minSeverity

    private fun password(): String {
        val file = File(credentials ?: error("ETEBASE_TEST_CREDENTIALS"))
        return Regex("\"${Regex.escape(user)}\"\\s*:\\s*\"([^\"]*)\"").find(file.readText())!!.groupValues[1]
    }

    private fun note(line: String) {
        log?.appendText(line + "\n")
        println(line)
    }

    private fun device(name: String, store: AttachmentStore = MemStore()) = Device(name, store).also { devices.add(it) }

    private fun newCollection(device: Device, name: String): String {
        val manager = device.etebase.collectionManager
        val col = manager.create(
            "etebase.vtodo",
            ItemMetadata().apply { this.name = "live4-$name-${System.currentTimeMillis()}" },
            "",
        )
        manager.upload(col)
        collectionUids.add(col.uid)
        return col.uid
    }

    private fun itemManager(device: Device, uid: String) =
        device.etebase.collectionManager.let { it.getItemManager(it.fetch(uid, null)) }

    /** Live items of a collection on the server, metadata only. */
    private fun items(device: Device, uid: String): List<Item> {
        val im = itemManager(device, uid)
        val result = ArrayList<Item>()
        var stoken: String? = null
        do {
            val r = im.list(FetchOptions().stoken(stoken).limit(100).prefetch(PrefetchOption.Medium))
            result.addAll(r.data.filterNot { it.isDeleted })
            stoken = r.stoken
        } while (!r.isDone)
        return result
    }

    private fun attachmentItems(device: Device, uid: String) =
        items(device, uid).filter { it.meta.itemType == EtebaseAttachments.ITEM_TYPE }

    private fun taskItems(device: Device, uid: String) =
        items(device, uid).filter { it.meta.itemType != EtebaseAttachments.ITEM_TYPE }

    @Before
    fun setUp() {
        assumeTrue("set ETEBASE_TEST_SERVER to run", url != null)
        Logger.setMinSeverity(Severity.Warn)
    }

    @After
    fun tearDown() {
        Logger.setMinSeverity(severity)
        val device = devices.firstOrNull() ?: return
        val manager = device.etebase.collectionManager
        collectionUids.forEach { uid ->
            runCatching { manager.fetch(uid, null).apply { delete() }.let { manager.upload(it) } }
        }
    }

    private fun random(size: Int, seed: Int) = ByteArray(size).also { java.util.Random(seed.toLong()).nextBytes(it) }

    /** The file of the second review that made every reader abort: 2 MiB that repeat in blocks. */
    private fun repeating(size: Int = 2 * 1024 * 1024) = ByteArray(size) { ((it * 31 + 1) xor (it shr 7)).toByte() }

    private fun foreignVtodo(uid: String, summary: String, vararg extra: String) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other client//EN\r\nBEGIN:VTODO\r\n")
        append("UID:$uid\r\nSUMMARY:$summary\r\nDTSTAMP:20261124T100000Z\r\n")
        extra.forEach { append(it) }
        append("END:VTODO\r\nEND:VCALENDAR\r\n")
    }

    @Test
    fun bytesTravelOnlyWhenAnAttachmentIsNew() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "bytes")
        a.sync("B0"); b.sync("B0")
        val big = random(2 * 1024 * 1024, 1)
        a.newTask(col, "t1")
        a.attach("t1", "a1", big)
        assertTrue(a.sync("B1 add 2 MiB on A").up in 2_800_000..3_100_000)
        assertTrue(a.sync("B1 idle A").down < 20_000) // its own upload does not come back
        assertTrue(b.sync("B1 receive B").down in 2_800_000..3_100_000)
        assertArrayEquals(big, b.content("t1").single())
        a.edit("t1") { notes = "edited on A" }
        assertTrue(a.sync("B2 notes on A").up < 20_000)
        assertTrue(b.sync("B2 receive B").down < 20_000)
        b.edit("t1") { completionDate = System.currentTimeMillis() }
        assertTrue(b.sync("B3 complete on B").up < 20_000)
        assertTrue(a.sync("B3 receive A").down < 20_000)
        b.attach("t1", "b1", random(1024 * 1024, 2))
        assertTrue(b.sync("B4 add 1 MiB on B").up in 1_400_000..1_600_000)
        assertTrue(a.sync("B4 receive A").down in 1_400_000..1_600_000)
        assertEquals(2, a.content("t1").size)
        a.remove("t1", "a1")
        assertTrue(a.sync("B5 remove a1 on A").up < 20_000)
        assertTrue(b.sync("B5 receive B").down < 20_000)
        assertEquals(1, b.content("t1").size)
        assertEquals(1, attachmentItems(a, col).size) // the item of a1 is deleted
        assertEquals(2, (b.store as MemStore).files.size) // the file of a1 stays on B
    }

    @Test
    fun concurrentAddsKeepBoth() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "concurrent")
        a.sync("K0 A")
        a.newTask(col, "c1")
        a.sync("K0 A"); b.sync("K0 B")
        a.attach("c1", "ka", random(100_000, 3))
        b.attach("c1", "kb", random(100_000, 4))
        b.edit("c1") { notes = "B edits too" }
        a.sync("K1 A")
        b.sync("K1 B")
        a.sync("K2 A")
        assertEquals(2, a.content("c1").size)
        assertEquals(2, b.content("c1").size)
        assertEquals("B edits too", a.task("c1").notes)
    }

    @Test
    fun repeatingContentIsReadBackByTheOtherDevice() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "repeating")
        a.sync("R0"); b.sync("R0")
        val pattern = repeating()
        val zeros = ByteArray(300 * 1024)
        a.newTask(col, "p1")
        a.attach("p1", "p1", pattern)
        a.newTask(col, "z1")
        a.attach("z1", "z1", zeros)
        a.newTask(col, "n1")
        a.edit("n1") { notes = "the same line again and again\n".repeat(4000) }
        a.sync("R1 A")
        b.sync("R1 B")
        assertArrayEquals(pattern, b.content("p1").single())
        assertArrayEquals(zeros, b.content("z1").single())
        assertEquals("the same line again and again\n".repeat(4000), b.task("n1").notes)
    }

    @Test
    fun unreadableItemsDoNotStopTheSync() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "unreadable")
        val im = itemManager(a, col)
        im.batch(
            arrayOf(
                im.create_raw(byteArrayOf(1, 2, 3), "x".toByteArray()), // metadata that cannot be decoded
                im.create(ItemMetadata().apply { name = "garbage" }, random(50_000, 5)), // not iCalendar
                im.create(ItemMetadata().apply { name = "nouid" }, foreignVtodo("", "no uid").replace("UID:\r\n", "").toByteArray()),
                im.create(
                    ItemMetadata().apply { name = "badref" },
                    foreignVtodo(
                        "badref", "bad reference",
                        "ATTACH;VALUE=URI;X-TASKS-SHA256=${"0".repeat(64)};X-TASKS-ID=x:x-etebase-item:doesnotexist\r\n",
                    ).toByteArray(),
                ),
            )
        )
        System.getenv("ETEBASE_TEST_PYTHON")?.let { python -> writeWithUnpatchedLibrary(python, col) }
        a.sync("U0 A")
        a.newTask(col, "good")
        a.sync("U1 A")
        b.sync("U1 B")
        assertEquals("good", b.task("good").title)
        assertEquals("bad reference", b.task("bad reference").title)
        a.edit("good") { notes = "later change" }
        a.sync("U2 A")
        b.sync("U2 B")
        assertEquals("later change", b.task("good").notes)
        b.newTask(col, "from B")
        b.sync("U3 B")
        a.sync("U3 A")
        assertEquals("from B", a.task("from B").title)
    }

    /** An item with content that has repeated chunks, written by the unpatched library of the Python binding. */
    private fun writeWithUnpatchedLibrary(python: String, col: String) {
        val script = """
            import json, sys
            from etebase import Client, Account
            url, user, creds, col = sys.argv[1:5]
            account = Account.login(Client("live", url), user, json.load(open(creds))[user])
            cm = account.get_collection_manager()
            im = cm.get_item_manager(cm.fetch(col))
            data = bytes(((i * 31 + 1) ^ (i >> 7)) & 255 for i in range(2 * 1024 * 1024))
            im.batch([im.create({"name": "unpatched"}, data)])
        """.trimIndent()
        val process = ProcessBuilder(python, "-I", "-c", script, url, user, credentials, col)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        note("item written by the unpatched library")
    }

    @Test
    fun moveBetweenListsKeepsAttachments() = runBlocking {
        val a = device("A")
        val c = device("C", AttachmentStore.None) // has none of the files, like desktop
        val col = newCollection(a, "move-from")
        val col2 = newCollection(a, "move-to")
        val data = random(500_000, 6)
        a.sync("M0 A")
        a.newTask(col, "m1")
        a.attach("m1", "m1", data)
        a.sync("M0 A")
        c.sync("M0 C")
        c.move("m1", col2)
        c.sync("M1 C moves") // col comes first: its deletion waits for the push to col2
        c.sync("M2 C")
        assertEquals(0, taskItems(a, col).size)
        assertEquals(1, attachmentItems(a, col).size) // the old item stays as an orphan
        assertEquals(1, taskItems(a, col2).size)
        assertEquals(1, attachmentItems(a, col2).size)
        val down = a.sync("M3 A receives the move").down
        assertTrue(down < 50_000) // A has the file: linked again, not downloaded
        assertArrayEquals(data, a.content("m1").single())
        assertEquals(a.calendar(col2).uuid, a.caldavDao.getTask(a.task("m1").id)!!.calendar)
    }

    @Test
    fun deletingATaskDeletesItsAttachmentItems() = runBlocking {
        val a = device("A")
        val col = newCollection(a, "delete")
        a.sync("D0 A")
        a.newTask(col, "d1")
        a.attach("d1", "d1", random(100_000, 7))
        a.sync("D1 A")
        assertEquals(1, attachmentItems(a, col).size)
        a.taskDeleter.markDeleted(a.task("d1"))
        a.sync("D2 A deletes")
        assertEquals(0, attachmentItems(a, col).size)
        assertEquals(0, taskItems(a, col).size)
    }

    @Test
    fun lostReplyTransfersNothingAgain() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "lost")
        a.sync("L0 A"); b.sync("L0 B")
        a.newTask(col, "l1")
        a.attach("l1", "l1", random(1_000_000, 8))
        a.loseNextUploadReply = true
        a.sync("L1 A, reply lost", expectError = true)
        val again = a.sync("L2 A")
        assertTrue(again.up < 20_000 && again.down < 20_000)
        assertEquals(1, attachmentItems(a, col).size)
        assertTrue(b.sync("L2 receive B").down in 1_300_000..1_500_000)
        // lost reply, then the user removes the attachment: nothing is downloaded
        a.attach("l1", "l2", random(300_000, 9))
        a.loseNextUploadReply = true
        a.sync("L3 A adds l2, reply lost", expectError = true)
        a.remove("l1", "l2")
        val removed = a.sync("L4 A removed l2")
        assertTrue(removed.down < 50_000)
    }

    @Test
    fun foreignInlineAttachmentIsConvertedOnFirstEdit() = runBlocking {
        val a = device("A")
        val b = device("B")
        val col = newCollection(a, "inline")
        val data = random(1024 * 1024, 10)
        val base64 = java.util.Base64.getEncoder().encodeToString(data)
        val attach = "ATTACH;ENCODING=BASE64;VALUE=BINARY;X-FILENAME=foreign.bin:$base64"
            .chunked(74).joinToString("\r\n ") + "\r\n"
        val im = itemManager(a, col)
        im.batch(arrayOf(im.create(ItemMetadata().apply { name = "f" }, foreignVtodo("f", "foreign", attach).toByteArray())))
        a.sync("I1 A"); b.sync("I1 B")
        assertArrayEquals(data, b.content("foreign").single())
        b.edit("foreign") { notes = "converted" }
        assertTrue(b.sync("I2 B converts").up in 1_400_000..1_600_000)
        assertTrue(a.sync("I2 receive A").down < 50_000) // A has the file already
        a.edit("foreign") { notes = "after" }
        assertTrue(a.sync("I3 A edits").up < 20_000)
        assertTrue(b.sync("I3 receive B").down < 20_000)
        assertEquals(1, a.content("foreign").size)
    }

    @Test
    fun firstSyncCostOf60Tasks() = runBlocking {
        val a = device("A")
        val col = newCollection(a, "sixty")
        a.sync("P0 A")
        repeat(60) { a.newTask(col, "task $it") }
        a.sync("P1 A pushes 60 tasks")
        val plain = device("B").sync("P2 new device, 60 tasks")
        repeat(60) { a.attach("task $it", "p$it", random(1024, 100 + it)) }
        a.sync("P3 A adds 60 x 1 KiB")
        val withFiles = device("C").sync("P4 new device, 60 tasks with 1 KiB each")
        note("first sync: plain ${plain.requests} requests ${plain.down} B; with files ${withFiles.requests} requests ${withFiles.down} B")
    }
}

private fun counting(body: ResponseBody, counter: AtomicLong): ResponseBody {
    val source = object : ForwardingSource(body.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long =
            super.read(sink, byteCount).also { if (it > 0) counter.addAndGet(it) }
    }
    return source.buffer().asResponseBody(body.contentType(), body.contentLength())
}
