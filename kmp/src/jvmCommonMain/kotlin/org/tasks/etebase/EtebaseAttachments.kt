package org.tasks.etebase

import co.touchlab.kermit.Logger
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.TaskAttachment
import org.tasks.files.AttachmentStore
import org.tasks.icalendar.ICalProperty
import org.tasks.icalendar.VTodo
import org.tasks.data.entity.MetadataTombstone
import org.tasks.etebase.EtebaseClient.Companion.isTransient
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

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

/**
 * Attachments of EteSync tasks. Every attachment is an etebase item of its own ([ITEM_TYPE]) in the collection of its
 * task, referenced from the VTODO by
 * `ATTACH;VALUE=URI;FMTTYPE=..;FILENAME=..;SIZE=..;X-TASKS-ID=<file id>;X-TASKS-SHA256=<hex>:x-etebase-item:<uid>`.
 * X-TASKS-ID is the id of its file row (`attachment_file.file_uuid`), the same on every device that has the file, and
 * identifies the attachment; the SHA-256 verifies a download. Items never change, so bytes travel only when an
 * attachment is added: one upload, and one download on each device that has no file with that id.
 *
 * Nothing here deletes a file. A reference is dropped only when the user removed the attachment on this device
 * ([removed], recorded in `metadata_tombstone`), and a link is removed only when another device dropped the reference.
 */
class EtebaseAttachments(
    private val dao: TaskAttachmentDao,
    private val store: AttachmentStore,
) {
    /**
     * After [remote] arrived for [task]: links what was added elsewhere (downloading it with [download] when this
     * device has no file with its id) and unlinks what was removed elsewhere. [base] is the VTODO this device had
     * before. A download that fails is recorded, shown in the editor and tried again ([downloadsDue]).
     */
    suspend fun applyRemote(
        task: Long,
        remote: List<AttachmentRef>,
        base: List<AttachmentRef>,
        download: suspend (AttachmentRef) -> ByteArray?,
    ) {
        if (!store.keepsFiles) return
        val removed = states(REMOVED, task).keys
        val local = dao.getAttachments(task)
        var count = local.size
        var bytes = local.sumOf { store.size(it.uri) ?: 0 }
        val failed = ArrayList<AttachmentRef>()
        for (ref in remote.distinctBy { it.id }) {
            if (ref.id in removed) continue // removed here, the next push drops it
            val linked = local.firstOrNull { it.remoteId == ref.id }
            if (linked != null && store.size(linked.uri) != null) continue
            // a row whose file is missing here (a restored backup) gets the file again, under the same id
            val known = linked ?: dao.getAttachment(ref.id)
            val file = known?.takeIf { linked == null && store.size(it.uri) != null }
                ?: if (problem(ref.size, count, bytes) != null) {
                    Logger.w("EtebaseAttachments") { "Not downloaded, over a limit: ${ref.size} bytes" }
                    null
                } else {
                    obtain(ref, known, download).also { if (it == null) failed.add(ref) }
                }
                ?: continue
            if (linked == null) {
                dao.insert(listOf(Attachment(task = task, fileId = file.id!!, attachmentUid = file.remoteId)))
                count++
            }
            bytes += ref.size
        }
        for (file in local) {
            if (remote.none { it.id == file.remoteId } && base.any { it.id == file.remoteId }) {
                dao.delete(task, file.remoteId) // removed on another device; the file stays
            }
        }
        dao.deleteStates(NOT_DOWNLOADED, "$task/")
        failed.forEach { setState(NOT_DOWNLOADED, task, it.id, it.name) }
    }

    /**
     * Before [vtodo] of [task] is pushed: drops the references that the user removed on this device (deleting their
     * items with [delete]), carries the attachments of the list the task was moved from ([carried]: uploaded from the
     * file on this device, else copied from the content of the old item that [copy] gives, which this device then
     * keeps too, else kept as they are) and adds the files that are new on this device ([create], with the file id
     * and the item content).
     */
    suspend fun applyLocal(
        task: Long,
        vtodo: VTodo,
        carried: List<AttachmentRef> = emptyList(),
        create: suspend (String, ByteArray) -> String,
        delete: suspend (String) -> Unit = {},
        copy: suspend (AttachmentRef) -> ByteArray? = { null },
    ) {
        val removed = states(REMOVED, task).keys
        val refused = states(NOT_UPLOADED, task).filterValues { !it.isDue(UPLOAD_RETRY) }.keys
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
            val data = local.firstOrNull { it.remoteId == ref.id && it.remoteId !in refused }
                ?.let { store.read(it.uri, MAX_BYTES) }
                ?.takeIf { sha256(it) == ref.sha256 }
            val content = if (data == null) tryOrNull { copy(ref) } else null
            val uid = data?.let { create(ref.id, itemContent(it)) }
                ?: content?.let { create(ref.id, it) }
                ?: ref.uid // when in doubt keep: the reference stays, its item stays in the old list
            if (content != null && store.keepsFiles && local.none { it.remoteId == ref.id }) {
                val known = dao.getAttachment(ref.id)
                (known?.takeIf { store.size(it.uri) != null } ?: obtain(ref, known) { fromItemContent(content) })
                    ?.let { dao.insert(listOf(Attachment(task = task, fileId = it.id!!, attachmentUid = it.remoteId))) }
            }
            val property = ref.property.copy(value = SCHEME + uid)
            properties.add(property)
            refs.add(ref.copy(property = property, uid = uid))
        }
        for (file in local) {
            if (refs.any { it.id == file.remoteId } || file.remoteId in refused) continue
            val size = store.size(file.uri) ?: continue
            if (problem(size, refs.size, refs.sumOf { it.size }) != null) continue // the editor tells why
            val data = store.read(file.uri, MAX_BYTES) ?: continue
            val property = reference(
                uid = create(file.remoteId, itemContent(data)),
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
     * The old item of [task], which was moved to another list, references [old]; its new item references [new].
     * Returns the attachment items of the old list to delete with the old item, or null while the new item lacks one
     * of them (it is carried first).
     */
    suspend fun leftBehind(task: Long, old: List<AttachmentRef>, new: List<AttachmentRef>): List<String>? {
        val removed = states(REMOVED, task).keys
        if (old.any { ref -> ref.id !in removed && new.none { it.id == ref.id } }) return null
        // items that the new list has a copy of, or that the user removed; one kept as it is stays
        return old.filter { ref -> ref.id in removed || new.any { it.id == ref.id && it.uid != ref.uid } }.map { it.uid }
    }

    /** [task] is gone. */
    suspend fun forget(task: Long) = listOf(REMOVED, NOT_DOWNLOADED, NOT_UPLOADED).forEach {
        dao.deleteStates(it, "$task/")
    }

    /** The user removed [files] from [task] on this device: the next push drops their references. */
    suspend fun removed(task: Long, files: Collection<TaskAttachment>) =
        files.forEach { setState(REMOVED, task, it.remoteId, it.name) }

    /** The user added [files] to [task] on this device (perhaps again). */
    suspend fun added(task: Long, files: Collection<TaskAttachment>) = files.forEach {
        dao.deleteStates(REMOVED, "$task/${it.remoteId}/")
        dao.deleteStates(NOT_UPLOADED, "$task/${it.remoteId}/")
    }

    /** [task] was pushed with new items for the files [uploaded]; [removalsDone]: no list still has its old item. */
    suspend fun pushed(task: Long, uploaded: Collection<String>, removalsDone: Boolean) {
        uploaded.forEach { dao.deleteStates(NOT_UPLOADED, "$task/$it/") }
        if (removalsDone) dao.deleteStates(REMOVED, "$task/")
    }

    /** The server refused the upload of [files] of [task] (e.g. HTTP 413 from a proxy): retried after a day. */
    suspend fun notUploaded(task: Long, files: Collection<String>) = files.forEach { id ->
        setState(NOT_UPLOADED, task, id, dao.getAttachment(id)?.name.orEmpty())
    }

    /** Tasks with a failed download to try again. */
    suspend fun downloadsDue(): List<Long> = due(NOT_DOWNLOADED, DOWNLOAD_RETRY)

    /** Tasks with a refused upload to try again. */
    suspend fun uploadsDue(): List<Long> = due(NOT_UPLOADED, UPLOAD_RETRY)

    /** Attachments of [task] that this device could not synchronize: file id to name and problem. */
    suspend fun failures(task: Long): Map<String, Pair<String, Problem>> =
        states(NOT_DOWNLOADED, task).mapValues { (_, state) -> state.name to Problem.NOT_DOWNLOADED } +
            states(NOT_UPLOADED, task).mapValues { (_, state) -> state.name to Problem.NOT_UPLOADED }

    private suspend fun <T> tryOrNull(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isTransient) throw e
            // one unreadable item never stops the others
            Logger.e("EtebaseAttachments", e) { "Skipped an attachment item" }
            null
        }

    private suspend fun obtain(
        ref: AttachmentRef,
        known: TaskAttachment?,
        download: suspend (AttachmentRef) -> ByteArray?,
    ): TaskAttachment? {
        val data = tryOrNull { download(ref) }
        if (data == null || data.size > MAX_BYTES || sha256(data) != ref.sha256) {
            Logger.w("EtebaseAttachments") { "Not downloaded: ${ref.uid}" }
            return null
        }
        val uri = store.create(ref.name, ref.mimeType, data) ?: return null
        if (known != null) {
            dao.update(known.copy(uri = uri)) // not insert: a replaced row would lose its links
            return known.copy(uri = uri)
        }
        val file = TaskAttachment(remoteId = ref.id, name = ref.name, uri = uri)
        dao.insert(file)
        return dao.getAttachment(file.remoteId)
    }

    private class State(val name: String, val time: Long) {
        fun isDue(delay: Long) = currentTimeMillis() - time >= delay
    }

    /** States of [category] for [task] by file id; keys are `<task>/<file id>/<name>`. */
    private suspend fun states(category: String, task: Long): Map<String, State> =
        dao.getStates(category, "$task/").associate {
            val (_, id, name) = it.key.split('/', limit = 3)
            id to State(name, it.ts)
        }

    private suspend fun setState(category: String, task: Long, id: String, name: String) {
        dao.deleteStates(category, "$task/$id/")
        dao.insertState(MetadataTombstone(category, "$task/$id/$name", currentTimeMillis()))
    }

    private suspend fun due(category: String, delay: Long): List<Long> =
        dao.getStates(category)
            .filter { currentTimeMillis() - it.ts >= delay }
            .mapNotNull { it.key.substringBefore('/').toLongOrNull() }
            .distinct()

    companion object {
        const val ITEM_TYPE = "org.tasks.attachment"
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_PER_TASK = 10
        const val MAX_TASK_BYTES = 20L * 1024 * 1024

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

        // categories in metadata_tombstone
        private const val REMOVED = "etebase_attachment_removed"
        private const val NOT_DOWNLOADED = "etebase_attachment_not_downloaded"
        private const val NOT_UPLOADED = "etebase_attachment_not_uploaded"
        private const val DOWNLOAD_RETRY = 60 * 60 * 1000L
        private const val UPLOAD_RETRY = 24 * 60 * 60 * 1000L

        enum class Problem { TOO_LARGE, TASK_LIMIT, NOT_DOWNLOADED, NOT_UPLOADED }

        /** Why an attachment of [size] bytes after [count] attachments of [bytes] in total does not sync. */
        fun problem(size: Long, count: Int, bytes: Long): Problem? = when {
            size > MAX_BYTES -> Problem.TOO_LARGE
            count >= MAX_PER_TASK || bytes + size > MAX_TASK_BYTES -> Problem.TASK_LIMIT
            else -> null
        }

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
                size = parameters["SIZE"]?.toLongOrNull() ?: 0,
            )
        }

        fun reference(uid: String, id: String, sha256: String, name: String, mimeType: String?, size: Long) =
            ICalProperty(
                name = "ATTACH",
                value = SCHEME + uid,
                parameters = listOfNotNull(
                    "VALUE" to "URI",
                    mimeType?.takeIf(MIME::matches)?.let { "FMTTYPE" to it },
                    "FILENAME" to name.replace(Regex("[\"\\p{Cntrl}]"), "_"),
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
