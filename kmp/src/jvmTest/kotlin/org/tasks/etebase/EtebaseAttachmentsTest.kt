package org.tasks.etebase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tasks.DatabaseTest
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.Task
import org.tasks.data.entity.TaskAttachment
import org.tasks.etebase.EtebaseAttachments.Companion.fromItemContent
import org.tasks.etebase.EtebaseAttachments.Companion.itemContent
import org.tasks.etebase.EtebaseAttachments.Companion.parse
import org.tasks.etebase.EtebaseAttachments.Companion.reference
import org.tasks.etebase.EtebaseAttachments.Companion.sha256
import org.tasks.files.AttachmentStore
import org.tasks.icalendar.ICalProperty
import org.tasks.icalendar.VTodo
import org.tasks.icalendar.parseVTodos
import org.tasks.icalendar.serialize
import kotlin.random.Random

class EtebaseAttachmentsTest : DatabaseTest() {
    private class MemStore : AttachmentStore {
        val files = linkedMapOf<String, ByteArray>()
        override suspend fun size(uri: String) = files[uri]?.size?.toLong()
        override suspend fun read(uri: String, maxBytes: Int) = files[uri]?.takeIf { it.size <= maxBytes }
        override suspend fun mimeType(uri: String) = "image/png"
        override suspend fun create(name: String, mimeType: String?, data: ByteArray) =
            "mem://${files.size}/$name".also { files[it] = data }
        override suspend fun delete(uri: String) = true.also { files.remove(uri) }
    }

    private val dao = db.taskAttachmentDao()
    private val store = MemStore()
    private val attachments = EtebaseAttachments(dao, store)
    private val MAX_BYTES = attachments.limits.maxBytes
    private val created = ArrayList<ByteArray>()
    private val deleted = ArrayList<String>()
    private val copied = ArrayList<String>()
    private val downloads = ArrayList<String>()
    private val server = HashMap<String, ByteArray>()

    private fun bytes(size: Int, seed: Int = size) = Random(seed).nextBytes(size)

    private fun newTask(): Long = runBlocking { Task().also { db.taskDao().createNew(it) }.id }

    private suspend fun addFile(task: Long?, id: String, data: ByteArray): TaskAttachment {
        val uri = "mem://local/$id"
        store.files[uri] = data
        dao.insert(TaskAttachment(remoteId = id, name = "$id.png", uri = uri))
        val file = dao.getAttachment(id)!!
        task?.let { dao.insert(listOf(Attachment(task = it, fileId = file.id!!, attachmentUid = id))) }
        return file
    }

    private fun ref(data: ByteArray, uid: String, id: String) =
        reference(uid, id, sha256(data), "$id.png", "image/png", data.size.toLong()).also { server[uid] = data }

    private fun vtodo(vararg properties: ICalProperty) =
        VTodo(uid = "t", unknownProperties = properties.toMutableList())

    private suspend fun push(task: Long, vtodo: VTodo, carried: List<AttachmentRef> = emptyList()) =
        attachments.applyLocal(
            task = task,
            vtodo = vtodo,
            carried = carried,
            create = { content -> created.add(fromItemContent(content)!!); "new${created.size}" },
            delete = { deleted.add(it) },
            copy = { ref -> copied.add(ref.uid); server[ref.uid]?.let(::itemContent) },
        )

    private suspend fun pull(task: Long, remote: VTodo, base: VTodo?) =
        attachments.applyRemote(task, parse(remote), base?.let(::parse).orEmpty()) { ref ->
            downloads.add(ref.uid)
            server[ref.uid]
        }

    /** What the editor does when the user removes an attachment. */
    private suspend fun removeHere(task: Long, id: String) {
        dao.delete(task, id)
        attachments.removed(task, listOf(dao.getAttachment(id)!!))
    }

    private suspend fun linked(task: Long) = dao.getAttachments(task).map { it.remoteId }.toSet()

    @Test
    fun itemContentRoundTripsAlsoForRepeatingContent() {
        for (data in listOf(ByteArray(0), bytes(1), ByteArray(300 * 1024), bytes(MAX_BYTES))) {
            val content = itemContent(data)
            assertArrayEquals(data, fromItemContent(content))
            assertEquals("BEGIN:VCALENDAR\r\n", content.copyOf(17).decodeToString())
        }
        assertNull(fromItemContent("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nEND:VTODO\r\nEND:VCALENDAR\r\n".encodeToByteArray()))
    }

    @Test
    fun referenceSurvivesTheVtodoRoundTrip() {
        val data = bytes(100)
        val property = reference("item_1-A", "file-1", sha256(data), "a \"b\";c:d,e.png", "image/png", 100)
        val text = VTodo(uid = "t", summary = "x", unknownProperties = mutableListOf(property)).serialize()
        val ref = parse(parseVTodos(text).single()).single()
        assertEquals("item_1-A", ref.uid)
        assertEquals("file-1", ref.id)
        assertEquals(sha256(data), ref.sha256)
        assertEquals("a _b_;c:d,e.png", ref.name)
        assertEquals(100L, ref.size)
    }

    @Test
    fun attachmentsOfOtherClientsAreNotReferences() {
        assertNull(parse(ICalProperty("ATTACH", "aGVsbG8=", listOf("ENCODING" to "BASE64", "VALUE" to "BINARY"))))
        assertNull(parse(ICalProperty("ATTACH", "https://example.com/a.png")))
        val withoutId = reference("u1", "f1", sha256(bytes(1)), "a.png", null, 1)
            .let { it.copy(parameters = it.parameters.filter { (name, _) -> name != "X-TASKS-ID" }) }
        assertNull(parse(withoutId))
    }

    @Test
    fun inlineAttachmentOfAnotherClientStaysAsItIs() = runBlocking {
        val task = newTask()
        val inline = ICalProperty("ATTACH", "aGVsbG8=", listOf("ENCODING" to "BASE64", "VALUE" to "BINARY"))
        val vtodo = vtodo(inline)
        push(task, vtodo)
        pull(task, vtodo, base = null)
        assertEquals(listOf(inline), vtodo.unknownProperties)
        assertEquals(emptyList<ByteArray>(), created)
        assertEquals(emptySet<String>(), linked(task))
    }

    @Test
    fun newFileIsUploadedOnceAndNotAgainOnLaterEdits() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        val vtodo = vtodo()
        push(task, vtodo)
        assertEquals(1, created.size)
        assertArrayEquals(data, created[0])
        val ref = parse(vtodo).single()
        assertEquals("f1", ref.id)
        assertEquals("new1", ref.uid)
        // the next edit: the cached VTODO has the reference
        push(task, vtodo)
        push(task, vtodo)
        assertEquals(1, created.size)
        assertEquals(1, parse(vtodo).size)
    }

    @Test
    fun fileThatCannotBeReadAtPushTimeStaysLinkedAndIsPushedLater() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        val saved = store.files.remove("mem://local/f1")!! // say the SD card is unmounted
        val vtodo = vtodo()
        push(task, vtodo)
        assertEquals(0, created.size)
        assertEquals(setOf("f1"), linked(task))
        store.files["mem://local/f1"] = saved
        push(task, vtodo)
        assertEquals(1, created.size)
        assertEquals("f1", parse(vtodo).single().id)
    }

    @Test
    fun placeholderCopiedWithADuplicateIsUnlinkedByThePush() = runBlocking {
        val task = newTask()
        dao.insert(TaskAttachment(remoteId = "f1", name = "f1.png", uri = ""))
        dao.insert(listOf(Attachment(task = task, fileId = dao.getAttachment("f1")!!.id!!, attachmentUid = "f1")))
        push(task, vtodo())
        assertEquals(emptySet<String>(), linked(task))
        assertEquals(0, created.size)
    }

    @Test
    fun removalOnThisDeviceDropsTheReferenceAndDeletesTheItem() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        val vtodo = vtodo(ref(data, "u1", "f1"))
        removeHere(task, "f1") // the file row stays
        push(task, vtodo)
        assertEquals(emptyList<AttachmentRef>(), parse(vtodo))
        assertEquals(listOf("u1"), deleted)
        assertTrue(store.files.containsKey("mem://local/f1"))
    }

    @Test
    fun referenceThatThisDeviceNeverHadIsKept() = runBlocking {
        val task = newTask()
        val vtodo = vtodo(ref(bytes(1000), "u1", "f1"))
        push(task, vtodo)
        assertEquals(1, parse(vtodo).size)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun referenceWhoseFileThisDeviceLostIsKept() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(null, "f1", data) // known row, not linked, file gone
        store.files.clear()
        val vtodo = vtodo(ref(data, "u1", "f1"))
        push(task, vtodo)
        assertEquals(1, parse(vtodo).size)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun limitsAreAppliedOnPush() = runBlocking {
        val task = newTask()
        addFile(task, "big", bytes(MAX_BYTES + 1))
        repeat(11) { addFile(task, "f$it", bytes(10, it)) }
        val vtodo = vtodo()
        push(task, vtodo)
        assertEquals(10, created.size)
        assertTrue(created.none { it.size > MAX_BYTES })
    }

    @Test
    fun moveCopiesWhatThisDeviceDoesNotHaveAndUploadsWhatItHas() = runBlocking {
        val task = newTask()
        val local = bytes(1000, 1)
        val remoteOnly = bytes(1000, 2)
        val removed = bytes(1000, 3)
        addFile(task, "local", local)
        addFile(task, "removed", removed)
        removeHere(task, "removed") // removed from the task after the move
        val old = parse(vtodo(ref(local, "o1", "local"), ref(remoteOnly, "o2", "remote"), ref(removed, "o3", "removed")))
        val vtodo = vtodo()
        push(task, vtodo, carried = old)
        assertEquals(listOf("o2"), copied)
        assertEquals(2, created.size)
        assertArrayEquals(local, created[0])
        assertArrayEquals(remoteOnly, created[1])
        assertEquals(setOf("new1", "new2"), parse(vtodo).map { it.uid }.toSet())
        assertEquals(setOf("local", "remote"), linked(task)) // the copy is kept here too
        // pushed again while the old item is still there: nothing is carried twice
        push(task, vtodo, carried = old)
        assertEquals(1, copied.size)
        assertEquals(2, created.size)
        // nothing is left to carry: the old item can go
        assertEquals(emptyList<AttachmentRef>(), attachments.toCarry(task, old, parse(vtodo)) { true })
    }

    /** REVIEW4 F1: a reference added to the old item during the move is carried by the next push. */
    @Test
    fun referenceAddedToTheOldItemDuringAMoveIsCarried() = runBlocking {
        val task = newTask()
        val f = bytes(1000, 1)
        val x = bytes(1000, 2)
        addFile(task, "f", f)
        val vtodo = vtodo()
        push(task, vtodo, carried = parse(vtodo(ref(f, "o1", "f"))))
        val old = parse(vtodo(ref(f, "o1", "f"), ref(x, "o2", "x")))
        assertEquals(listOf("x"), attachments.toCarry(task, old, parse(vtodo)) { true }.map { it.id })
        push(task, vtodo, carried = attachments.toCarry(task, old, parse(vtodo)) { true })
        assertEquals(listOf("o2"), copied)
        assertEquals(emptyList<AttachmentRef>(), attachments.toCarry(task, old, parse(vtodo)) { true })
    }

    /** An attachment of the old item that this device has no file of and never saw in the old list is gone there. */
    @Test
    fun attachmentNeverSeenInTheOldListIsNotCarried() = runBlocking {
        val task = newTask()
        val f = bytes(1000, 1)
        addFile(task, "f", f)
        val old = parse(vtodo(ref(f, "o1", "f"), ref(bytes(1000, 2), "o2", "x")))
        assertEquals(listOf("f"), attachments.toCarry(task, old, emptyList()) { false }.map { it.id })
    }

    @Test
    fun referenceThatCannotBeCopiedIsKeptAsItIs() = runBlocking {
        val task = newTask()
        val old = parse(vtodo(ref(bytes(1000), "o1", "f")))
        val vtodo = vtodo()
        attachments.applyLocal(
            task = task,
            vtodo = vtodo,
            carried = old,
            create = { error("nothing to upload") },
            copy = { throw IllegalStateException("Got an invalid chunk index") },
        )
        assertEquals(listOf("o1"), parse(vtodo).map { it.uid })
        assertEquals(emptyList<AttachmentRef>(), attachments.toCarry(task, old, parse(vtodo)) { true })
    }

    @Test
    fun remoteAdditionIsDownloadedOnceUnderItsId() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        val remote = vtodo(ref(data, "u1", "f1"))
        pull(task, remote, base = null)
        assertEquals(listOf("u1"), downloads)
        assertEquals(setOf("f1"), linked(task))
        pull(task, remote, base = remote)
        assertEquals(1, downloads.size)
    }

    @Test
    fun fileThatAnotherTaskUsesIsLinkedWithoutDownload() = runBlocking {
        val other = newTask()
        val task = newTask()
        val data = bytes(1000)
        addFile(other, "f1", data)
        pull(task, vtodo(ref(data, "u2", "f1")), base = null)
        assertEquals(emptyList<String>(), downloads)
        assertEquals(setOf("f1"), linked(task))
    }

    @Test
    fun remoteRemovalUnlinksButKeepsTheFile() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        val base = vtodo(ref(data, "u1", "f1"))
        pull(task, vtodo(), base)
        assertEquals(emptySet<String>(), linked(task))
        assertTrue(store.files.containsKey("mem://local/f1"))
        assertEquals("f1", dao.getAttachment("f1")?.remoteId)
    }

    @Test
    fun localAdditionNotYetPushedIsKept() = runBlocking {
        val task = newTask()
        addFile(task, "f1", bytes(1000))
        pull(task, vtodo(), base = vtodo())
        assertEquals(setOf("f1"), linked(task))
    }

    @Test
    fun localRemovalIsNotUndoneByAPull() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        removeHere(task, "f1")
        val remote = vtodo(ref(data, "u1", "f1"))
        pull(task, remote, base = remote)
        assertEquals(emptySet<String>(), linked(task))
        assertEquals(emptyList<String>(), downloads)
    }

    @Test
    fun lostReplyThenLocalRemovalTransfersNothing() = runBlocking {
        // REVIEW3 M1: the push with the attachment arrived, its reply did not; the user removed it meanwhile
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        removeHere(task, "f1")
        val remote = vtodo(ref(data, "u1", "f1"))
        pull(task, remote, base = vtodo())
        assertEquals(emptyList<String>(), downloads)
        assertEquals(emptySet<String>(), linked(task))
        push(task, remote) // and the removal is pushed, it does not come back
        assertEquals(listOf("u1"), deleted)
    }

    @Test
    fun failedDownloadLeavesTheReferenceForTheNextPush() = runBlocking {
        val task = newTask()
        val remote = vtodo(ref(bytes(1000), "u1", "f1"))
        server.clear()
        pull(task, remote, base = null)
        // a placeholder: the editor shows it, without file
        assertEquals(setOf("f1"), linked(task))
        assertEquals("", dao.getAttachment("f1")!!.uri)
        push(task, remote)
        assertEquals(1, parse(remote).size)
        assertEquals(emptyList<String>(), deleted)
        assertEquals(emptyList<ByteArray>(), created)
        // the next change of the task on the server tries again
        server["u1"] = bytes(1000)
        pull(task, remote, base = remote)
        assertArrayEquals(bytes(1000), store.files[dao.getAttachment("f1")!!.uri])
        assertEquals(setOf("f1"), linked(task))
    }

    @Test
    fun wrongContentIsNotStored() = runBlocking {
        val task = newTask()
        val remote = vtodo(ref(bytes(1000), "u1", "f1"))
        server["u1"] = bytes(1000, 99)
        pull(task, remote, base = null)
        assertEquals("", dao.getAttachment("f1")!!.uri) // only a placeholder
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun limitsAreAppliedOnPull() = runBlocking {
        val task = newTask()
        val big = reference("u1", "f1", sha256(bytes(1)), "big.bin", null, MAX_BYTES + 1L)
        val many = (0 until 12).map { ref(bytes(10, it), "m$it", "m$it") }
        pull(task, vtodo(big, *many.toTypedArray()), base = null)
        assertEquals(10, downloads.size)
        assertTrue("u1" !in downloads)
    }

    @Test
    fun linkedFileThatIsMissingHereIsDownloadedAgain() = runBlocking {
        // a restored backup: the row and its link are back, the file is not
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "f1", data)
        store.files.clear()
        val remote = vtodo(ref(data, "u1", "f1"))
        pull(task, remote, base = remote)
        assertEquals(listOf("u1"), downloads)
        assertEquals(setOf("f1"), linked(task))
        assertArrayEquals(data, store.files[dao.getAttachment("f1")!!.uri])
    }

    /** REVIEW4 F3: one unreadable reference does not stop the others, and a reference never linked here is not a removal. */
    @Test
    fun unreadableReferenceDoesNotStopTheOthersAndIsNotTakenForARemoval() = runBlocking {
        val t1 = newTask()
        val t2 = newTask()
        val f = bytes(1000, 1)
        addFile(t1, "F", f)
        // t2 is a copy of t1 made on another device: an item of its own, the same file id
        val remote = vtodo(ref(bytes(1000, 9), "uBad", "BAD"), ref(f, "uF2", "F"))
        server.remove("uBad")
        runCatching {
            attachments.applyRemote(t2, parse(remote), emptyList()) { r ->
                if (r.uid == "uBad") throw IllegalStateException("Got an invalid chunk index")
                server[r.uid]
            }
        }
        assertEquals(setOf("F", "BAD"), linked(t2))
        assertEquals("", dao.getAttachment("BAD")!!.uri) // a placeholder
        push(t2, vtodo(*remote.unknownProperties.toTypedArray()))
        assertEquals(emptyList<String>(), deleted)
    }

    // files that sync wrote are deleted once nothing needs them (the phone test of 25-11: they stayed for good)

    private suspend fun uri(id: String) = dao.getAttachment(id)?.uri

    /** Device B received [id] for [task] (downloaded: written by sync). */
    private suspend fun received(task: Long, id: String, data: ByteArray = bytes(1000, id.hashCode())): ByteArray {
        val remote = vtodo(ref(data, "u-$id", id))
        pull(task, remote, base = null)
        assertArrayEquals(data, store.files[uri(id)])
        return data
    }

    private suspend fun deleteTask(task: Long) = db.deletionDao().delete(listOf(task)) {}

    @Test
    fun fileRemovedElsewhereIsDeletedWithItsRow() = runBlocking {
        val task = newTask()
        val data = received(task, "f1")
        val file = uri("f1")!!
        attachments.cleanUp()
        assertArrayEquals("linked: kept", data, store.files[file])
        pull(task, vtodo(), base = vtodo(ref(data, "u-f1", "f1")))
        assertEquals(emptySet<String>(), linked(task))
        attachments.cleanUp()
        assertNull(store.files[file])
        assertNull(dao.getAttachment("f1"))
        assertEquals(emptyList<String>(), dao.getSynced())
    }

    @Test
    fun fileThatTheUserPickedIsNeverDeleted() = runBlocking {
        val task = newTask()
        val data = bytes(1000)
        addFile(task, "mine", data)
        val remote = vtodo(ref(data, "u1", "mine"))
        pull(task, vtodo(), base = remote) // removed on another device
        assertEquals(emptySet<String>(), linked(task))
        attachments.cleanUp()
        assertArrayEquals(data, store.files["mem://local/mine"])
        assertEquals("mem://local/mine", uri("mine"))
        // also when the user copied a file into the attachments directory itself: no row, no record
        store.files["mem://0/copied.png"] = data
        attachments.cleanUp()
        assertArrayEquals(data, store.files["mem://0/copied.png"])
    }

    @Test
    fun fileLinkedByTwoTasksStaysWhenOneUnlinksIt() = runBlocking {
        val t1 = newTask()
        val t2 = newTask()
        val data = received(t1, "f1")
        // a duplicate made on another device: another item, the same file id, linked without download
        val copy = vtodo(ref(data, "u-copy", "f1"))
        pull(t2, copy, base = null)
        assertEquals(listOf("u-f1"), downloads)
        pull(t1, vtodo(), base = vtodo(ref(data, "u-f1", "f1")))
        attachments.cleanUp()
        assertEquals(setOf("f1"), linked(t2))
        assertArrayEquals(data, store.files[uri("f1")])
        pull(t2, vtodo(), base = copy)
        val file = uri("f1")!!
        attachments.cleanUp()
        assertNull(store.files[file])
    }

    @Test
    fun fileOfATaskDeletedElsewhereIsDeleted() = runBlocking {
        val task = newTask()
        received(task, "f1")
        received(task, "f2")
        val files = listOf(uri("f1")!!, uri("f2")!!)
        deleteTask(task)
        attachments.cleanUp()
        files.forEach { assertNull(store.files[it]) }
        assertNull(dao.getAttachment("f1"))
        assertNull(dao.getAttachment("f2"))
    }

    @Test
    fun fileOfAMovedTaskStaysWithTheMovedCopy() = runBlocking {
        // another device moved the task: here its old row goes, the task in the new list links the same file
        val old = newTask()
        val data = received(old, "f1")
        val moved = newTask()
        pull(moved, vtodo(ref(data, "u-new", "f1")), base = null)
        deleteTask(old)
        attachments.cleanUp()
        assertEquals(listOf("u-f1"), downloads)
        assertEquals(setOf("f1"), linked(moved))
        assertArrayEquals(data, store.files[uri("f1")])
    }

    @Test
    fun fileRemovedHereStaysUntilTheRemovalIsPushed() = runBlocking {
        val task = newTask()
        received(task, "f1")
        val file = uri("f1")!!
        removeHere(task, "f1")
        attachments.cleanUp()
        assertTrue("removal not pushed yet: kept", store.files.containsKey(file))
        val vtodo = vtodo(ref(store.files[file]!!, "u-f1", "f1"))
        push(task, vtodo)
        attachments.pushed(task, parse(vtodo).map { it.id }, moved = false)
        attachments.cleanUp()
        assertNull(store.files[file])
    }

    @Test
    fun rowThatNamesAnotherFileNowKeepsBoth() = runBlocking {
        val task = newTask()
        received(task, "f1")
        val file = uri("f1")!!
        dao.update(dao.getAttachment("f1")!!.copy(uri = "mem://local/other"))
        store.files["mem://local/other"] = bytes(5)
        dao.delete(task, "f1")
        attachments.cleanUp()
        assertTrue(store.files.containsKey(file))
        assertTrue(store.files.containsKey("mem://local/other"))
        assertEquals(emptyList<String>(), dao.getSynced()) // only the record goes
    }

    @Test
    fun fileCopiedFromAnOldItemIsWrittenBySync() = runBlocking {
        // a move carried by a device without the file: it copies the item and keeps the file, which sync wrote
        val task = newTask()
        val data = bytes(1000)
        val old = parse(vtodo(ref(data, "u-old", "f1")))
        push(task, vtodo(), carried = old)
        assertEquals(listOf("u-old"), copied)
        val file = uri("f1")!!
        dao.delete(task, "f1")
        attachments.cleanUp()
        assertNull(store.files[file])
    }
}
