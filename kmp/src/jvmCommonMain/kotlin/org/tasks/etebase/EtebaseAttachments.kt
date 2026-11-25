package org.tasks.etebase

import co.touchlab.kermit.Logger
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.MetadataTombstone
import org.tasks.data.entity.TaskAttachment
import org.tasks.etebase.EtebaseClient.Companion.isTransient
import org.tasks.files.AttachmentStore
import org.tasks.icalendar.ICalProperty
import org.tasks.icalendar.VTodo
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** An attachment as a VTODO names it: a reference to the attachment item [uid]. */
data class AttachmentRef(
    val property: ICalProperty,
    val uid: String,
    val id: String,
    val sha256: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
)

/** Size limits of attachments that sync: per file, and per task (number and bytes). */
data class AttachmentLimits(
    val maxBytes: Int = 10 * 1024 * 1024,
    val maxPerTask: Int = 10,
    val maxTaskBytes: Long = 20L * 1024 * 1024,
)

/**
 * Attachments of EteSync tasks. Every attachment is an etebase item of its own ([ITEM_TYPE]) in the collection of its
 * task, referenced from the VTODO by
 * `ATTACH;VALUE=URI;FMTTYPE=..;FILENAME=..;SIZE=..;X-TASKS-ID=<file id>;X-TASKS-SHA256=<hex>:x-etebase-item:<uid>`.
 * X-TASKS-ID is the id of its file row (`attachment_file.file_uuid`), the same on every device that has the file, and
 * identifies the attachment; the SHA-256 verifies a download. Items never change, so bytes travel only when an
 * attachment is added: one upload, and one download on each device that has no file with that id.
 *
 * Nothing here deletes a file. A reference is dropped only when the user removed the attachment on some device: on
 * this device that is recorded ([removed]) until the push; elsewhere it shows as a reference that the new VTODO lacks.
 * An attachment that this device could not download is linked to a file row without file (a placeholder), which the
 * next change of the task on the server tries to fill, as a row restored from a backup.
 */
class EtebaseAttachments(
    private val dao: TaskAttachmentDao,
    private val store: AttachmentStore,
    val limits: AttachmentLimits = AttachmentLimits(),
) {
    /**
     * After [remote] arrived for [task]: links what was added elsewhere (downloading it with [download] when this
     * device has no file with its id; null when it is not available) and unlinks what was removed elsewhere. [base] is
     * the VTODO this device had before. [moving]: the old item of the task (moved on this device) is still in its old
     * list; what was removed elsewhere is then recorded, so that the old item does not carry it back.
     */
    suspend fun applyRemote(
        task: Long,
        remote: List<AttachmentRef>,
        base: List<AttachmentRef>,
        moving: Boolean = false,
        download: suspend (AttachmentRef) -> ByteArray?,
    ) {
        if (!store.keepsFiles) return
        val removed = dao.getRemoved(task)
        val local = dao.getAttachments(task)
        val present = local.filter { store.size(it.uri) != null }
        var count = present.size
        var bytes = present.sumOf { store.size(it.uri) ?: 0 }
        // the downloads first, the links once they all worked: a download that fails (and is tried again) leaves the
        // links as they were, consistent with the base, which is then not written either
        val links = ArrayList<TaskAttachment>()
        for (ref in remote.distinctBy { it.id }) {
            if (ref.id in removed) continue // removed here, the next push drops it
            val linked = local.firstOrNull { it.remoteId == ref.id }
            if (linked != null && store.size(linked.uri) != null) continue
            val known = linked ?: dao.getAttachment(ref.id)
            val file = known?.takeIf { store.size(it.uri) != null } // another task has the file
                ?: if (problem(ref.size, count, bytes) == null) obtain(ref, known, download) else null
            if (file != null) {
                count++
                bytes += ref.size
            }
            // without the file: a placeholder, so that the editor shows it and a later change tries again
            val row = file ?: known ?: run {
                dao.insert(TaskAttachment(remoteId = ref.id, name = ref.name, uri = ""))
                dao.getAttachment(ref.id)!!
            }
            if (linked == null) links.add(row)
        }
        links.forEach { link(task, it) }
        for (file in local) {
            if (remote.none { it.id == file.remoteId } && base.any { it.id == file.remoteId }) {
                dao.delete(task, file.remoteId) // removed on another device; the file stays
                if (moving) dao.insertRemoved(MetadataTombstone(TaskAttachmentDao.REMOVED, "$task/${file.remoteId}", currentTimeMillis()))
            }
        }
    }

    /**
     * Before [vtodo] of [task] is pushed: drops the references that the user removed on this device (deleting their
     * items with [delete]), carries the attachments [carried] from the list the task was moved from (see [toCarry];
     * uploaded from the file on this device, else copied from the content of the old item that [copy] gives, which
     * this device then keeps too; an old item that cannot be read stays referenced as it is) and adds the files that
     * are new on this device ([create], with the item content).
     */
    suspend fun applyLocal(
        task: Long,
        vtodo: VTodo,
        carried: List<AttachmentRef> = emptyList(),
        create: suspend (ByteArray) -> String,
        delete: suspend (String) -> Unit = {},
        copy: suspend (AttachmentRef) -> ByteArray? = { null },
    ) {
        val removed = dao.getRemoved(task)
        val local = dao.getAttachments(task)
        val properties = vtodo.unknownProperties
        val refs = parse(vtodo).toMutableList()
        for (ref in refs.filter { it.id in removed }) {
            properties.remove(ref.property)
            refs.remove(ref)
            delete(ref.uid)
        }
        for (ref in carried) {
            if (ref.id in removed || refs.any { it.id == ref.id }) continue
            // the file of this device, also one that another task of it has (a copy made with duplicate)
            val known = local.firstOrNull { it.remoteId == ref.id } ?: dao.getAttachment(ref.id)
            val data = known?.let { store.read(it.uri, limits.maxBytes) }?.takeIf { sha256(it) == ref.sha256 }
            val uid = if (data != null) {
                if (local.none { it.remoteId == ref.id }) link(task, known)
                create(itemContent(data))
            } else {
                val content = try {
                    copy(ref) ?: continue // gone from the old list: removed there
                } catch (e: Exception) {
                    if (e.isTransient) throw e
                    Logger.e("EtebaseAttachments", e) { "Cannot copy ${ref.uid}, kept as it is" }
                    null
                }
                if (content != null && local.none { it.remoteId == ref.id }) {
                    // this device keeps the copy too, so that a retry does not download it again
                    obtain(ref, dao.getAttachment(ref.id)) { fromItemContent(content) }?.let { link(task, it) }
                }
                content?.let { create(it) } ?: ref.uid
            }
            val property = ref.property.copy(value = SCHEME + uid)
            properties.add(property)
            refs.add(ref.copy(property = property, uid = uid))
        }
        for (file in local) {
            if (refs.any { it.id == file.remoteId }) continue
            val size = store.size(file.uri) ?: run {
                // a placeholder (no uri) copied with a duplicated task: no attachment. A file that cannot be read now
                // (storage unmounted, permission lost) stays linked and is pushed when it can be read again
                if (file.uri.isEmpty()) dao.delete(task, file.remoteId)
                continue
            }
            if (problem(size, refs.size, refs.sumOf { it.size }) != null) continue // the editor tells why
            val data = store.read(file.uri, limits.maxBytes) ?: continue
            val property = reference(
                uid = create(itemContent(data)),
                id = file.remoteId,
                sha256 = sha256(data),
                name = file.name,
                mimeType = store.mimeType(file.uri),
                size = data.size.toLong(),
            )
            properties.add(property)
            refs.add(parse(property)!!)
        }
    }

    /**
     * What a push of [task], which was moved to another list, carries from its old item [old] while it is there: the
     * references the new item [new] lacks that the user did not remove here and that can be carried: this device has
     * the file, or the old list has the item ([seen]: an item this device listed there; one it never saw is gone).
     */
    suspend fun toCarry(
        task: Long,
        old: List<AttachmentRef>,
        new: List<AttachmentRef>,
        seen: suspend (AttachmentRef) -> Boolean,
    ): List<AttachmentRef> {
        val removed = dao.getRemoved(task)
        return old.filter { ref ->
            ref.id !in removed && new.none { it.id == ref.id } && (hasFile(ref.id) || seen(ref))
        }
    }

    /**
     * [task] has a move of this device that is not done, and another device removed attachments of it: what [before]
     * has and [after] lacks (the old item as it changed on the server, or the copy that the other device moved on) is
     * removed here too and recorded, so that the old item does not carry it back. Returns whether there was such a
     * removal (the task is then pushed again).
     */
    suspend fun removedElsewhere(task: Long, before: List<AttachmentRef>, after: List<AttachmentRef>): Boolean {
        val gone = before.filter { ref -> after.none { it.id == ref.id } }
        for (ref in gone) {
            dao.delete(task, ref.id)
            dao.insertRemoved(MetadataTombstone(TaskAttachmentDao.REMOVED, "$task/${ref.id}", currentTimeMillis()))
        }
        return gone.isNotEmpty()
    }

    /** Whether attachments of [task] were added (a file not in [base]) or removed on this device and not pushed. */
    suspend fun changedHere(task: Long, base: List<AttachmentRef>): Boolean =
        dao.getRemoved(task).isNotEmpty() || addedHere(task, base).isNotEmpty()

    /** What was changed here on [from] (see [changedHere]) is done on [to]: the same task, moved by another device. */
    suspend fun transfer(from: Long, to: Long, base: List<AttachmentRef>) {
        val there = dao.getAttachments(to).map { it.remoteId }
        addedHere(from, base).filter { it.remoteId !in there }.forEach { link(to, it) }
        dao.getRemoved(from).forEach { id ->
            dao.delete(to, id)
            dao.insertRemoved(MetadataTombstone(TaskAttachmentDao.REMOVED, "$to/$id", currentTimeMillis()))
        }
    }

    private suspend fun addedHere(task: Long, base: List<AttachmentRef>) =
        dao.getAttachments(task).filter { file -> base.none { it.id == file.remoteId } && store.size(file.uri) != null }

    /** [task] was pushed with references to [pushed] (file ids); [moved]: an old item of it is still in another list. */
    suspend fun pushed(task: Long, pushed: Collection<String>, moved: Boolean) {
        if (moved) return // until the old item is gone, it must not carry the removed ones back
        // a removal made during the push is still in the pushed VTODO: the next push drops it
        dao.getRemoved(task).filter { it !in pushed }.forEach { dao.deleteRemoved(task, it) }
    }

    /** [task] is gone. */
    suspend fun forget(task: Long) = dao.deleteRemoved(task)

    /** The user removed [files] from [task] on this device: the next push drops their references. */
    suspend fun removed(task: Long, files: Collection<TaskAttachment>) = files.forEach {
        dao.insertRemoved(MetadataTombstone(TaskAttachmentDao.REMOVED, "$task/${it.remoteId}", currentTimeMillis()))
    }

    /** The user added [files] to [task] on this device (perhaps again). */
    suspend fun added(task: Long, files: Collection<TaskAttachment>) = files.forEach {
        dao.deleteRemoved(task, it.remoteId)
    }

    /** Why an attachment of [size] bytes after [count] attachments of [bytes] in total does not sync. */
    fun problem(size: Long, count: Int, bytes: Long): Problem? = when {
        size > limits.maxBytes -> Problem.TOO_LARGE
        count >= limits.maxPerTask || bytes + size > limits.maxTaskBytes -> Problem.TASK_LIMIT
        else -> null
    }

    private suspend fun hasFile(id: String) = dao.getAttachment(id)?.let { store.size(it.uri) } != null

    private suspend fun link(task: Long, file: TaskAttachment) =
        dao.insert(listOf(Attachment(task = task, fileId = file.id!!, attachmentUid = file.remoteId)))

    /** Downloads [ref] and stores it in a new file, for the row [known] if there is one; null if that fails. */
    private suspend fun obtain(
        ref: AttachmentRef,
        known: TaskAttachment?,
        download: suspend (AttachmentRef) -> ByteArray?,
    ): TaskAttachment? {
        val data = try {
            download(ref)
        } catch (e: Exception) {
            if (e.isTransient) throw e
            Logger.e("EtebaseAttachments", e) { "Cannot read ${ref.uid}" } // one unreadable item never stops the others
            null
        }
        if (data == null || data.size > limits.maxBytes || data.size.toLong() != ref.size || sha256(data) != ref.sha256) {
            Logger.w("EtebaseAttachments") { "Not downloaded: ${ref.uid}" }
            return null
        }
        val uri = store.create(ref.name, ref.mimeType, data) ?: return null
        if (known != null) {
            dao.update(known.copy(uri = uri)) // not insert: a replaced row would lose its links
            return known.copy(uri = uri)
        }
        dao.insert(TaskAttachment(remoteId = ref.id, name = ref.name, uri = uri))
        return dao.getAttachment(ref.id)
    }

    companion object {
        const val ITEM_TYPE = "org.tasks.attachment"

        private const val SCHEME = "x-etebase-item:"
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val MIME = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        private val HEADER = (
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:+//IDN tasks.org//attachment//EN\r\n" +
                "BEGIN:X-TASKS-ATTACHMENT\r\nATTACH;ENCODING=BASE64;VALUE=BINARY:"
            ).encodeToByteArray()
        private val FOOTER = "\r\nEND:X-TASKS-ATTACHMENT\r\nEND:VCALENDAR\r\n".encodeToByteArray()
        private const val LINE = 74

        enum class Problem { TOO_LARGE, TASK_LIMIT, NOT_DOWNLOADED }

        fun parse(vtodo: VTodo): List<AttachmentRef> = vtodo.unknownProperties.mapNotNull(::parse)

        /** A reference written by this build; null for anything else, which stays in the VTODO as it is. */
        fun parse(property: ICalProperty): AttachmentRef? {
            if (!property.name.equals("ATTACH", ignoreCase = true) || !property.value.startsWith(SCHEME)) return null
            val parameters = property.parameters.associate { (name, value) -> name.uppercase() to value }
            return AttachmentRef(
                property = property,
                uid = property.value.removePrefix(SCHEME).takeIf(ID::matches) ?: return null,
                id = parameters["X-TASKS-ID"]?.takeIf(ID::matches) ?: return null,
                sha256 = parameters["X-TASKS-SHA256"]?.lowercase()?.takeIf(HEX64::matches) ?: return null,
                name = parameters["FILENAME"]?.takeIf(String::isNotBlank) ?: "attachment",
                mimeType = parameters["FMTTYPE"]?.takeIf(MIME::matches),
                size = parameters["SIZE"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }

        fun reference(uid: String, id: String, sha256: String, name: String, mimeType: String?, size: Long) =
            ICalProperty(
                name = "ATTACH",
                value = SCHEME + uid,
                parameters = listOfNotNull(
                    "VALUE" to "URI",
                    mimeType?.takeIf(MIME::matches)?.let { "FMTTYPE" to it },
                    "FILENAME" to name.replace(Regex("[\"\\p{Cntrl}\\p{Cf}]"), "_"),
                    "SIZE" to size.toString(),
                    "X-TASKS-ID" to id,
                    "X-TASKS-SHA256" to sha256,
                ),
            )

        /** The content of an attachment item: a VCALENDAR without VTODO, which other EteSync clients skip. */
        fun itemContent(data: ByteArray): ByteArray {
            val base64 = Base64.getEncoder().encode(data)
            val lines = (base64.size + LINE - 1) / LINE
            val out = ByteBuffer.allocate(HEADER.size + base64.size + lines * 3 + FOOTER.size)
            out.put(HEADER)
            for (start in base64.indices step LINE) {
                out.put(FOLD)
                out.put(base64, start, minOf(LINE, base64.size - start))
            }
            out.put(FOOTER)
            return out.array()
        }

        /** The file in an attachment item, or null when [content] is not one. */
        fun fromItemContent(content: ByteArray): ByteArray? {
            val start = content.indexOf(HEADER, 0).takeIf { it >= 0 }?.plus(HEADER.size) ?: return null
            val end = content.indexOf(FOOTER, start).takeIf { it >= 0 } ?: return null
            return try {
                Base64.getMimeDecoder().decode(ByteBuffer.wrap(content, start, end - start)).let {
                    ByteArray(it.remaining()).also(it::get)
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private val FOLD = "\r\n ".encodeToByteArray()

        private fun ByteArray.indexOf(pattern: ByteArray, from: Int): Int {
            outer@ for (i in from..size - pattern.size) {
                for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
