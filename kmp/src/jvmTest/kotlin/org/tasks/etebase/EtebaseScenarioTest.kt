package org.tasks.etebase

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tasks.etebase.sim.Fault
import org.tasks.etebase.sim.FaultKind
import org.tasks.etebase.sim.SimDevice
import org.tasks.etebase.sim.SimWorld

/**
 * Sequences of ordinary actions with the real library, client and synchronizer, against the in-memory server, or
 * against a real one (throwaway collections, deleted afterwards) with
 * `ETEBASE_TEST_SERVER=http://127.0.0.1:3735 ETEBASE_TEST_USER=test1 ETEBASE_TEST_CREDENTIALS=creds.json`
 * (creds.json maps user names to passwords).
 */
class EtebaseScenarioTest {
    private val world = System.getenv("ETEBASE_TEST_SERVER").let { url ->
        if (url == null) {
            SimWorld(trace = { if (System.getenv("SIM_TRACE") != null) println(it) })
        } else {
            val user = System.getenv("ETEBASE_TEST_USER") ?: "test1"
            val credentials = java.io.File(System.getenv("ETEBASE_TEST_CREDENTIALS")!!).readText()
            SimWorld(url, user, Regex("\"${Regex.escape(user)}\"\\s*:\\s*\"([^\"]*)\"").find(credentials)!!.groupValues[1])
        }
    }
    private lateinit var l1: String
    private lateinit var l2: String
    private val severity = Logger.config.minSeverity

    @Before
    fun setUp() {
        Logger.setMinSeverity(Severity.Error)
        l1 = world.newCollection("L1")
        l2 = world.newCollection("L2")
    }

    @After
    fun tearDown() {
        world.close()
        Logger.setMinSeverity(severity)
    }

    private fun device(name: String, keepsFiles: Boolean = true) = world.device(name, keepsFiles).also { synced(it) }

    private fun synced(device: SimDevice) {
        val result = device.sync()
        assertEquals("sync error on ${device.name}", "", result.error)
        assertEquals("exceptions on ${device.name}", emptyList<Throwable>(), device.exceptions)
    }

    private fun titles(list: String) = world.view().tasks[list]!!.values.map { it.summary }.toSet()

    private fun refs(list: String, title: String) =
        world.view().tasks[list]!!.values.single { it.summary == title }.let(EtebaseAttachments::parse).map { it.id }.toSet()

    private suspend fun SimDevice.task(title: String) = tasks().single { it.first.title == title }.first.id

    private fun attachmentDownloads(device: SimDevice): Int {
        val attachments = world.view().attachments.values.flatMap { it.keys }.toSet()
        return device.http.downloaded.filterKeys { it in attachments }.values.sum()
    }

    private fun attachmentUploads(device: SimDevice): Int {
        val attachments = world.view().attachments.values.flatMap { it.keys }.toSet()
        return device.http.uploaded.keys.count { it in attachments }
    }

    /** Syncs [device] and returns how many attachment items it uploaded and downloaded. */
    private fun transfers(device: SimDevice): Pair<Int, Int> {
        val up = attachmentUploads(device)
        val down = attachmentDownloads(device)
        synced(device)
        return (attachmentUploads(device) - up) to (attachmentDownloads(device) - down)
    }

    private fun attachmentItems(list: String) = world.view().attachments[list]!!.values.filterNotNull()

    private fun taskItems(list: String) = world.view().tasks[list]!!.values

    private suspend fun SimDevice.files(title: String) = files(task(title)).mapValues { it.value?.toList() }

    private fun foreignVtodo(uid: String, summary: String, vararg extra: String) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other client//EN\r\nBEGIN:VTODO\r\n")
        append("UID:$uid\r\nSUMMARY:$summary\r\nDTSTAMP:20261124T100000Z\r\n")
        extra.forEach { append(it) }
        append("END:VTODO\r\nEND:VCALENDAR\r\n")
    }

    private fun random(size: Int, seed: Int) = ByteArray(size).also { java.util.Random(seed.toLong()).nextBytes(it) }

    /** REVIEW5 F1: a task deleted before its first push (its obj is "<uid>.ics", which the server does not know). */
    @Test
    fun deleteBeforeFirstPush() = runBlocking {
        val a = device("A")
        a.delete(a.newTask(l1, "gone").id)
        a.newTask(l1, "other")
        repeat(3) { synced(a) }
        assertEquals(setOf("other"), titles(l1))
    }

    /** REVIEW5 F1: a task moved before its first push. */
    @Test
    fun moveBeforeFirstPush() = runBlocking {
        val a = device("A")
        a.move(a.newTask(l1, "fresh").id, l2)
        a.newTask(l1, "other")
        repeat(2) { synced(a) }
        assertEquals(setOf("other"), titles(l1))
        assertEquals(setOf("fresh"), titles(l2))
    }

    /**
     * REVIEW5 F2: a proxy refuses the upload of a carried attachment. The other lists sync, the attachment is not
     * downloaded again for every attempt, and the move completes once the proxy accepts it.
     */
    @Test
    fun moveWithRefusedCarriedUpload() = runBlocking {
        val a = device("A")
        val b = device("B")
        val t = b.newTask(l1, "t").id
        b.attach(t, "big.bin", random(300_000, 61))
        synced(b)
        synced(a)
        a.move(a.task("t"), l2)
        a.newTask(l1, "other")
        val big = { r: org.tasks.etebase.sim.Request -> r.isTransaction && r.body!!.size > 100_000 }
        repeat(3) {
            a.http.faults.add(Fault(FaultKind.REFUSE, big, "upload > 100 kB"))
            assertTrue("the refusal is shown", a.sync().error.isNotEmpty())
        }
        assertEquals(setOf("t", "other"), titles(l1)) // the old item stays until the carry is done
        val downloads = attachmentDownloads(a)
        assertTrue("downloaded $downloads times", downloads <= 1)
        synced(a)
        synced(a)
        synced(b)
        assertEquals(setOf("other"), titles(l1))
        assertEquals(setOf("t"), titles(l2))
        assertEquals(1, refs(l2, "t").size)
        assertEquals(random(300_000, 61).toList(), b.files(b.task("t")).values.single()!!.toList())
    }

    /** REVIEW5 F3: the user removes an attachment while a push of the task is under way. */
    @Test
    fun removalDuringPush() = runBlocking {
        val a = device("A")
        val b = device("B")
        val t = a.newTask(l1, "t").id
        val x1 = a.attach(t, "x1.bin", random(50_000, 71)).remoteId
        synced(a)
        synced(b)
        val x2 = a.attach(t, "x2.bin", random(50_000, 72)).remoteId
        a.http.hooks.add({ r: org.tasks.etebase.sim.Request -> r.isTransaction } to { runBlocking { a.detach(t, x1) } })
        synced(a)
        synced(a)
        synced(b)
        synced(a)
        assertEquals(setOf(x2), a.files(t).keys)
        assertEquals(setOf(x2), b.files(b.task("t")).keys)
        assertEquals(setOf(x2), refs(l1, "t"))
    }

    /** REVIEW5 F4: the uploading device keeps no copy of the attachments it uploads in the etebase cache. */
    @Test
    fun noCacheCopyOfUploads() = runBlocking {
        val a = device("A")
        val b = device("B")
        val t = a.newTask(l1, "c").id
        a.attach(t, "c.bin", random(500_000, 81))
        synced(a)
        synced(b)
        fun du(d: SimDevice) = d.cacheDir.walk().filter { it.isFile }.sumOf { it.length() }
        assertTrue("uploader cache ${du(a)} B", du(a) < 100_000)
        assertTrue("downloader cache ${du(b)} B", du(b) < 100_000)
    }

    /** Invariant 4: bytes travel only when an attachment is new, both directions. */
    @Test
    fun bytesTravelOnlyWhenAnAttachmentIsNew() = runBlocking {
        val a = device("A")
        val b = device("B")
        val t = a.newTask(l1, "t1").id
        val a1 = a.attach(t, "a1.bin", random(200_000, 1)).remoteId
        assertEquals(1 to 0, transfers(a))
        assertEquals(0 to 0, transfers(a)) // its own upload does not come back
        assertEquals(0 to 1, transfers(b))
        a.edit(t) { notes = "edited on A" }
        assertEquals(0 to 0, transfers(a))
        assertEquals(0 to 0, transfers(b))
        b.edit(b.task("t1")) { completionDate = 1 }
        assertEquals(0 to 0, transfers(b))
        assertEquals(0 to 0, transfers(a))
        b.attach(b.task("t1"), "b1.bin", random(100_000, 2))
        assertEquals(1 to 0, transfers(b))
        assertEquals(0 to 1, transfers(a))
        a.detach(t, a1)
        assertEquals(0 to 0, transfers(a))
        assertEquals(0 to 0, transfers(b))
        assertEquals(1, b.files("t1").size)
        assertEquals(1, attachmentItems(l1).size) // the item of a1 is deleted, its file stays on B
        assertTrue(b.store.dir.list()!!.size >= 2)
    }

    @Test
    fun concurrentAddsKeepBoth() = runBlocking {
        val a = device("A")
        val t = a.newTask(l1, "c1").id
        synced(a)
        val b = device("B")
        a.attach(t, "ka.bin", random(10_000, 3))
        b.attach(b.task("c1"), "kb.bin", random(10_000, 4))
        b.edit(b.task("c1")) { notes = "B edits too" }
        synced(a)
        synced(b)
        synced(a)
        assertEquals(2, a.files("c1").size)
        assertEquals(2, b.files("c1").size)
        assertEquals("B edits too", a.taskDao.fetch(t)!!.notes)
    }

    /** The content that made the unpatched library abort: blocks that repeat, and zeros. */
    @Test
    fun repeatingContentIsReadBackByTheOtherDevice() = runBlocking {
        val a = device("A")
        val b = device("B")
        val pattern = ByteArray(2 * 1024 * 1024) { ((it * 31 + 1) xor (it shr 7)).toByte() }
        val zeros = ByteArray(300 * 1024)
        a.attach(a.newTask(l1, "p1").id, "p1.bin", pattern)
        a.attach(a.newTask(l1, "z1").id, "z1.bin", zeros)
        a.edit(a.newTask(l1, "n1").id) { notes = "the same line again and again\n".repeat(4000) }
        synced(a)
        synced(b)
        assertEquals(pattern.toList(), b.files("p1").values.single())
        assertEquals(zeros.toList(), b.files("z1").values.single())
        assertEquals("the same line again and again\n".repeat(4000), b.taskDao.fetch(b.task("n1"))!!.notes)
    }

    /** Invariant 2: items that cannot be read are skipped, the rest syncs, also later. */
    @Test
    fun unreadableItemsDoNotStopTheSync() = runBlocking {
        val a = device("A")
        val b = device("B")
        val im = world.itemManager(l1)
        im.batch(
            arrayOf(
                im.create_raw(byteArrayOf(1, 2, 3), "x".toByteArray()), // metadata that cannot be decoded
                im.create(com.etebase.client.ItemMetadata().apply { name = "garbage" }, random(50_000, 5)), // not iCalendar
                im.create(
                    com.etebase.client.ItemMetadata().apply { name = "nouid" },
                    foreignVtodo("", "no uid").replace("UID:\r\n", "").toByteArray(),
                ),
                im.create(
                    com.etebase.client.ItemMetadata().apply { name = "badref" },
                    foreignVtodo(
                        "badref", "bad reference",
                        "ATTACH;VALUE=URI;X-TASKS-SHA256=${"0".repeat(64)};X-TASKS-ID=x:x-etebase-item:doesnotexist\r\n",
                    ).toByteArray(),
                ),
            ),
            emptyArray(),
            null,
        )
        synced(a)
        a.newTask(l1, "good")
        synced(a)
        synced(b)
        a.edit(a.task("good")) { notes = "later change" }
        synced(a)
        synced(b)
        assertEquals("later change", b.taskDao.fetch(b.task("good"))!!.notes)
        b.newTask(l1, "from B")
        synced(b)
        synced(a)
        assertEquals(setOf("good", "bad reference", "from B"), a.tasks().map { it.first.title }.toSet() - setOf("no uid"))
    }

    /** A device without files (desktop) moves a task: its attachment is copied into the new list, nothing is lost. */
    @Test
    fun moveBetweenListsKeepsAttachments() = runBlocking {
        val a = device("A")
        val c = device("C", keepsFiles = false)
        val data = random(300_000, 6)
        a.attach(a.newTask(l1, "m1").id, "m1.bin", data)
        synced(a)
        synced(c)
        c.move(c.task("m1"), l2)
        synced(c)
        synced(c)
        assertEquals(0, taskItems(l1).size)
        assertEquals(0, attachmentItems(l1).size) // deleted with the old item
        assertEquals(1, taskItems(l2).size)
        assertEquals(1, attachmentItems(l2).size)
        assertEquals(0 to 0, transfers(a)) // A has the file: linked again, not downloaded
        assertEquals(data.toList(), a.files("m1").values.single())
        assertEquals(a.calendar(l2).uuid, a.caldavDao.getTask(a.task("m1"))!!.calendar)
    }

    /** REVIEW4 F1 and F6: an attachment added on another device while a move is under way is carried. */
    @Test
    fun attachmentAddedElsewhereDuringAMoveIsCarried() = runBlocking {
        val a = device("A")
        val c = device("C")
        val f1 = a.attach(a.newTask(l1, "t").id, "f1.bin", random(50_000, 41)).remoteId
        synced(a)
        synced(c)
        a.move(a.task("t"), l2)
        val x = c.attach(c.task("t"), "x.bin", random(60_000, 42)).remoteId // C has not seen the move yet
        synced(c)
        repeat(2) { synced(a); synced(c) }
        assertEquals(setOf(f1, x), a.files("t").keys)
        assertEquals(setOf(f1, x), c.files("t").keys)
        assertEquals(0, taskItems(l1).size)
        assertEquals(0, attachmentItems(l1).size) // F6: no orphans in the old list
        assertEquals(2, attachmentItems(l2).size)
    }

    /** Seed 25 of the sequence test: an attachment added while another device moves the task is not lost. */
    @Test
    fun attachmentAddedWhileTheMoveIsDoneElsewhere() = runBlocking {
        val a = device("A")
        val c = device("C")
        a.newTask(l1, "t")
        synced(a)
        synced(c)
        a.move(a.task("t"), l2)
        synced(a) // the move is done: the old item is deleted
        synced(a)
        val x = c.attach(c.task("t"), "x.bin", random(20_000, 43)).remoteId
        synced(c)
        synced(c)
        synced(a)
        assertEquals(setOf(x), a.files("t").keys)
        assertEquals(setOf(x), c.files("t").keys)
        assertEquals(1, taskItems(l2).size)
        assertEquals(0, taskItems(l1).size)
    }

    @Test
    fun deletingATaskDeletesItsAttachmentItems() = runBlocking {
        val a = device("A")
        a.attach(a.newTask(l1, "d1").id, "d1.bin", random(100_000, 7))
        synced(a)
        assertEquals(1, attachmentItems(l1).size)
        a.delete(a.task("d1"))
        synced(a)
        assertEquals(0, attachmentItems(l1).size)
        assertEquals(0, taskItems(l1).size)
    }

    @Test
    fun lostReplyTransfersNothingAgain() = runBlocking {
        val a = device("A")
        val b = device("B")
        val t = a.newTask(l1, "l1").id
        a.attach(t, "l1.bin", random(100_000, 8))
        a.http.faults.add(Fault(FaultKind.LOSE_REPLY, { it.isTransaction }, "upload"))
        assertTrue(a.sync().error.isNotEmpty())
        assertEquals(0 to 0, transfers(a))
        assertEquals(1, attachmentItems(l1).size)
        assertEquals(0 to 1, transfers(b))
        // lost reply, then the user removes the attachment: nothing is downloaded, the removal is pushed
        val second = a.attach(t, "l2.bin", random(30_000, 9)).remoteId
        a.http.faults.add(Fault(FaultKind.LOSE_REPLY, { it.isTransaction }, "upload"))
        assertTrue(a.sync().error.isNotEmpty())
        a.detach(t, second)
        assertEquals(0 to 0, transfers(a))
        assertEquals(1, refs(l1, "l1").size)
    }

    /** The one documented exception of invariant 4: an inline ATTACH of another client travels with its VTODO. */
    @Test
    fun foreignInlineAttachmentStaysAsItIs() = runBlocking {
        val a = device("A")
        val b = device("B")
        val base64 = java.util.Base64.getEncoder().encodeToString(random(100_000, 10))
        val attach = "ATTACH;ENCODING=BASE64;VALUE=BINARY;X-FILENAME=foreign.bin:$base64".chunked(74).joinToString("\r\n ") + "\r\n"
        val im = world.itemManager(l1)
        im.batch(arrayOf(im.create(com.etebase.client.ItemMetadata().apply { name = "f" }, foreignVtodo("f", "foreign", attach).toByteArray())), emptyArray(), null)
        synced(a)
        synced(b)
        assertEquals(0, b.files("foreign").size) // not an attachment of this build: left alone
        b.edit(b.task("foreign")) { notes = "edited" }
        synced(b)
        synced(a)
        val vtodo = world.view().tasks[l1]!!.values.single()
        assertTrue(vtodo.unknownProperties.any { it.name == "ATTACH" && it.parameters.any { (_, v) -> v == "foreign.bin" } })
        assertEquals("edited", vtodo.description)
    }

    /** Requests of a first sync (one per task item: there is no fetch of several items by uid in etebase-java). */
    @Test
    fun firstSyncCostOf60Tasks() = runBlocking {
        val a = device("A")
        repeat(60) { a.newTask(l1, "task $it") }
        synced(a)
        val b = world.device("B")
        synced(b)
        val plain = b.http.requests - b.http.collectionLists
        repeat(60) { a.attach(a.task("task $it"), "p$it.bin", random(1024, 100 + it)) }
        synced(a)
        val c = world.device("C")
        synced(c)
        val withFiles = c.http.requests - c.http.collectionLists
        println("first sync (without listing collections): 60 tasks $plain requests; with 60 x 1 KiB $withFiles requests")
        assertTrue(plain < 70)
        assertTrue(withFiles < 190)
    }

    /** REVIEW5 N4: a list whose colour cannot be parsed does not stop the other lists (upstream ended the account). */
    @Test
    fun badColourEndsOnlyItsList() = runBlocking {
        val a = device("A")
        val manager = world.collectionManager
        val collection = manager.fetch(l1, null)
        collection.meta = collection.meta.apply { color = "#zzzzzz" }
        manager.upload(collection, null)
        a.newTask(l2, "other")
        assertTrue("shown", a.sync().error.isNotEmpty())
        assertEquals(setOf("other"), titles(l2))
    }

    /**
     * REVIEW6 H1 (partly fixed): a proxy refuses every request body above 100 kB. The refusal is the account error, the
     * other tasks sync; the refused task stays dirty and is tried again by every sync (known limitation) until the
     * proxy accepts it.
     */
    @Test
    fun refusedUploadIsShownAndTheOtherTasksSync() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.proxyLimit = 100_000
        a.restart()
        val big = a.newTask(l1, "big").id
        synced(a)
        a.attach(big, "photo.jpg", random(300_000, 71))
        a.newTask(l1, "other")
        repeat(2) { assertTrue("the refusal is shown", a.sync().error.isNotEmpty()) }
        assertEquals(setOf("big", "other"), titles(l1))
        assertEquals(emptySet<String>(), refs(l1, "big"))
        a.proxyLimit = null
        a.restart()
        synced(a)
        synced(b)
        assertEquals(1, refs(l1, "big").size)
        assertEquals(random(300_000, 71).toList(), b.files(b.task("big")).values.single()!!.toList())
    }

    /** REVIEW6 H1: a member who may only read a list adds an attachment there: shown as the account error. */
    @Test
    fun readOnlyListIsShown() = runBlocking {
        val a = device("A")
        a.newTask(l1, "shared")
        synced(a)
        val b = device("B")
        b.readOnly = setOf(l1)
        b.restart()
        b.attach(b.task("shared"), "scan.pdf", random(20_000, 72))
        b.newTask(l2, "own")
        assertTrue("the refusal is shown", b.sync().error.isNotEmpty())
        assertEquals(setOf("own"), titles(l2))
        assertEquals(emptySet<String>(), refs(l1, "shared"))
    }

    /** REVIEW6 L1: an item that the server cannot give stops the pushes of its list; that is shown, not silent. */
    @Test
    fun itemThatTheServerCannotGiveIsShown() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.newTask(l1, "broken")
        a.newTask(l1, "fine")
        synced(a)
        a.edit(a.task("broken")) { notes = "changed" }
        synced(a)
        val uid = world.view().tasks[l1]!!.entries.single { it.value.summary == "broken" }.key
        val broken = { r: org.tasks.etebase.sim.Request -> r.isItemGet && r.path.contains(uid) }
        repeat(50) { b.http.faults.add(Fault(FaultKind.SERVER_ERROR, broken, "GET broken")) }
        b.newTask(l2, "elsewhere")
        assertTrue("the 500 is shown", b.sync().error.isNotEmpty())
        assertEquals(setOf("elsewhere"), titles(l2)) // the other lists sync
        b.edit(b.task("fine")) { notes = "edited on B" }
        assertTrue("the 500 is shown", b.sync().error.isNotEmpty())
        b.http.faults.clear()
        synced(b)
        assertEquals("edited on B", world.view().tasks[l1]!!.values.single { it.summary == "fine" }.description)
        assertEquals("changed", b.tasks().single { it.first.title == "broken" }.first.notes)
    }

    /** REVIEW6: the account removed and logged in again: the files on the device are linked again, not downloaded. */
    @Test
    fun loginAgainLinksTheFilesWithoutDownload() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.attach(a.newTask(l1, "t").id, "a.bin", random(20_000, 73))
        synced(a)
        synced(b)
        val downloads = attachmentDownloads(b)
        b.logout()
        synced(b)
        assertEquals(downloads, attachmentDownloads(b))
        assertEquals(random(20_000, 73).toList(), b.files(b.task("t")).values.single()!!.toList())
        b.edit(b.task("t")) { notes = "after login" }
        synced(b)
        assertEquals(1, refs(l1, "t").size)
        assertEquals(1, world.view().tasks[l1]!!.values.count { it.summary == "t" })
    }

    /** REVIEW6: a device without files (desktop) moves a task with an attachment there and back. */
    @Test
    fun deviceWithoutFilesMovesATask() = runBlocking {
        val a = device("A")
        val d = device("D", keepsFiles = false)
        a.attach(a.newTask(l1, "t").id, "a.bin", random(40_000, 74))
        synced(a)
        synced(d)
        d.move(d.task("t"), l2)
        repeat(2) { synced(d); synced(a) }
        assertEquals(emptySet<String>(), titles(l1))
        assertEquals(1, refs(l2, "t").size)
        d.move(d.task("t"), l1)
        repeat(2) { synced(d); synced(a) }
        assertEquals(emptySet<String>(), titles(l2))
        assertEquals(1, refs(l1, "t").size)
        assertEquals(random(40_000, 74).toList(), a.files(a.task("t")).values.single()!!.toList())
    }

    /** REVIEW6: a parent and its subtask with an attachment are moved; then the parent is deleted on another device. */
    @Test
    fun subtaskWithAttachmentMovesWithItsParent() = runBlocking {
        val a = device("A")
        val b = device("B")
        val parent = a.newTask(l1, "parent")
        val child = a.newSubtask(parent.id, "child")!!
        a.attach(child.id, "c.bin", random(10_000, 75))
        synced(a)
        synced(b)
        a.move(parent.id, l2)
        b.attach(b.task("child"), "c2.bin", random(10_000, 76))
        synced(b)
        repeat(2) { synced(a); synced(b) }
        assertEquals(setOf("parent", "child"), titles(l2))
        assertEquals(emptySet<String>(), titles(l1))
        assertEquals(2, refs(l2, "child").size)
        assertEquals(2, a.files(a.task("child")).values.count { it != null })
        b.delete(b.task("parent"))
        synced(b)
        synced(a)
        assertEquals(emptyList<String>(), a.tasks().map { it.first.title })
    }
}
