package org.tasks.etebase

import co.touchlab.kermit.Logger
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.TaskAttachment
import org.tasks.files.AttachmentStore
import org.tasks.icalendar.ICalProperty
import org.tasks.icalendar.VTodo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * An attachment as a VTODO names it: a reference to an attachment item ([uid]), or inline content of another
 * client ([inline]).
 */
class AttachmentRef(
    val property: ICalProperty,
    val sha256: String,
    val uid: String?,
    val id: String?,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val inline: ByteArray?,
)

/**
 * Attachments of EteSync tasks. Every attachment is an etebase item of its own ([ITEM_TYPE]) in the collection of its
 * task, referenced from the VTODO by
 * `ATTACH;VALUE=URI;FMTTYPE=..;FILENAME=..;SIZE=..;X-TASKS-ID=<file id>;X-TASKS-SHA256=<hex>:x-etebase-item:<uid>`.
 * An attachment is identified by the SHA-256 of its content. X-TASKS-ID is the id of its file row
 * (`attachment_file.file_uuid`), the same on every device that has the file, so a device knows whether it ever had
 * it. Items never change, so bytes travel only when an attachment is added: one upload, and one download on each
 * device that has no file with that hash. Editing anything else moves only the VTODO.
 *
 * Nothing here deletes a file. A link between a task and a file is removed only when a user removed the attachment
 * on some device; when in doubt, a reference stays in the VTODO.
 */
class EtebaseAttachments(
    private val dao: TaskAttachmentDao,
    private val store: AttachmentStore,
) {
    private val hashes = HashMap<String, String?>()

    /**
     * After [remote] arrived for [task]: links what was added elsewhere (downloading it with [download] when no file
     * of this device has it) and unlinks what was removed elsewhere. [base] is the VTODO this device had before.
     */
    suspend fun applyRemote(
        task: Long,
        remote: List<AttachmentRef>,
        base: List<AttachmentRef>,
        dirty: Boolean,
        download: suspend (AttachmentRef) -> ByteArray?,
    ) {
        if (!store.keepsFiles) return
        val local = dao.getAttachments(task)
        var count = local.size
        var bytes = local.sumOf { store.size(it.uri) ?: 0 }
        for (ref in remote.distinctBy { it.sha256 }) {
            val linked = local.firstOrNull { matches(ref, it) }
            if (linked != null) {
                // a row whose file is missing here (a restored backup) gets the file again
                if (store.size(linked.uri) == null && problem(ref.size, count, bytes) == null) {
                    obtain(ref, linked, download)?.let { bytes += ref.size }
                }
                continue
            }
            if (dirty && base.any { it.sha256 == ref.sha256 } && removedHere(ref, local)) {
                continue // the next push drops it
            }
            val known = ref.id?.let { dao.getAttachment(it) }
            val file = known?.takeIf { hashOf(it) == ref.sha256 }
                ?: run {
                    if (problem(ref.size, count, bytes) != null) {
                        Logger.w("EtebaseAttachments") { "Not downloaded, over a limit: ${ref.size} bytes" }
                        return@run null
                    }
                    obtain(ref, known, download)
                }
                ?: continue
            dao.insert(listOf(Attachment(task = task, fileId = file.id!!, attachmentUid = file.remoteId)))
            count++
            bytes += store.size(file.uri) ?: 0
        }
        for (file in local) {
            if (remote.none { matches(it, file) } && base.any { matches(it, file) }) {
                dao.delete(task, file.remoteId) // removed on another device; the file stays
            }
        }
    }

    /**
     * Before [vtodo] of [task] is pushed: drops references that the user removed on this device (deleting their
     * items with [delete]), turns valid inline attachments into items, carries the attachments of the list a task
     * was moved from ([carried], copied with [copy]) and adds the files that are new on this device ([create]).
     */
    suspend fun applyLocal(
        task: Long,
        vtodo: VTodo,
        carried: List<AttachmentRef> = emptyList(),
        create: suspend (ByteArray) -> String,
        delete: suspend (String) -> Unit = {},
        copy: suspend (AttachmentRef) -> String? = { null },
    ) {
        val local = dao.getAttachments(task)
        val properties = vtodo.unknownProperties
        for (ref in carried) {
            if (local.any { matches(ref, it) } || removedHere(ref, local)) continue
            if (ref.uid == null) {
                properties.add(ref.property)
            } else {
                copy(ref)?.let { properties.add(ref.property.copy(value = SCHEME + it)) }
            }
        }
        for (ref in parse(vtodo)) {
            if (removedHere(ref, local)) {
                properties.remove(ref.property)
                ref.uid?.let { delete(it) }
            } else if (ref.inline != null) {
                val others = parse(vtodo).filter { it.property !== ref.property }
                if (problem(ref.size, others.size, others.sumOf { it.size }) != null) continue
                properties[properties.indexOf(ref.property)] = reference(
                    uid = create(itemContent(ref.inline)),
                    id = local.firstOrNull { matches(ref, it) }?.remoteId ?: ref.id!!,
                    sha256 = ref.sha256,
                    name = ref.name,
                    mimeType = ref.mimeType,
                    size = ref.size,
                )
            }
        }
        for (file in local) {
            val refs = parse(vtodo)
            if (refs.any { matches(it, file) }) continue
            val size = store.size(file.uri) ?: continue
            if (problem(size, refs.size, refs.sumOf { it.size }) != null) continue // the editor tells why
            val data = store.read(file.uri, MAX_BYTES) ?: continue
            val sha256 = sha256(data)
            hashes[file.uri] = sha256
            properties.add(
                reference(
                    uid = create(itemContent(data)),
                    id = file.remoteId,
                    sha256 = sha256,
                    name = file.name,
                    mimeType = store.mimeType(file.uri),
                    size = data.size.toLong(),
                )
            )
        }
    }

    /**
     * Whether the user removed [ref] from the task on this device: this device has its file (same id and content),
     * but the task does not link it. A file this device never had, or lost, is never a removal.
     */
    private suspend fun removedHere(ref: AttachmentRef, local: List<TaskAttachment>): Boolean =
        local.none { matches(ref, it) } &&
            ref.id?.let { dao.getAttachment(it) }?.let { hashOf(it) == ref.sha256 } == true

    private suspend fun obtain(
        ref: AttachmentRef,
        known: TaskAttachment?,
        download: suspend (AttachmentRef) -> ByteArray?,
    ): TaskAttachment? {
        val data = ref.inline ?: download(ref) ?: return null
        if (data.size > MAX_BYTES || sha256(data) != ref.sha256) {
            Logger.w("EtebaseAttachments") { "Download does not match its reference" }
            return null
        }
        val uri = store.create(ref.name, ref.mimeType, data) ?: return null
        hashes[uri] = ref.sha256
        if (known != null) {
            dao.update(known.copy(uri = uri)) // not insert: a replaced row would lose its links
            return known.copy(uri = uri)
        }
        val file = TaskAttachment(remoteId = ref.id ?: UUIDHelper.newUUID(), name = ref.name, uri = uri)
        dao.insert(file)
        return dao.getAttachment(file.remoteId)
    }

    private suspend fun matches(ref: AttachmentRef, file: TaskAttachment) =
        (ref.id != null && ref.id == file.remoteId) || hashOf(file) == ref.sha256

    private suspend fun hashOf(file: TaskAttachment): String? =
        hashes.getOrPut(file.uri) { store.read(file.uri, MAX_BYTES)?.let(::sha256) }

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

        enum class Problem { TOO_LARGE, TASK_LIMIT }

        /** Why an attachment of [size] bytes after [count] attachments of [bytes] in total does not sync. */
        fun problem(size: Long, count: Int, bytes: Long): Problem? = when {
            size > MAX_BYTES -> Problem.TOO_LARGE
            count >= MAX_PER_TASK || bytes + size > MAX_TASK_BYTES -> Problem.TASK_LIMIT
            else -> null
        }

        fun parse(vtodo: VTodo): List<AttachmentRef> = vtodo.unknownProperties.mapNotNull(::parse)

        /**
         * A reference or valid inline content up to [MAX_BYTES]; null for anything else, which stays in the VTODO as
         * it is (also malformed or larger inline content of other clients, the one exception to bytes travelling
         * only when they are new).
         */
        fun parse(property: ICalProperty): AttachmentRef? {
            if (!property.name.equals("ATTACH", ignoreCase = true)) return null
            val parameters = property.parameters.associate { (name, value) -> name.uppercase() to value }
            val name = listOf("FILENAME", "X-FILENAME", "X-EVOLUTION-CALDAV-ATTACHMENT-NAME", "X-APPLE-FILENAME")
                .firstNotNullOfOrNull { parameters[it]?.takeIf(String::isNotBlank) }
                ?: "attachment"
            val mimeType = parameters["FMTTYPE"]?.takeIf(MIME::matches)
            val id = parameters["X-TASKS-ID"]?.takeIf(ID::matches)
            return if (property.value.startsWith(SCHEME)) {
                AttachmentRef(
                    property = property,
                    sha256 = parameters["X-TASKS-SHA256"]?.lowercase()?.takeIf(HEX64::matches) ?: return null,
                    uid = property.value.removePrefix(SCHEME).takeIf(ID::matches) ?: return null,
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    size = parameters["SIZE"]?.toLongOrNull() ?: 0,
                    inline = null,
                )
            } else if (parameters["ENCODING"].equals("BASE64", ignoreCase = true)) {
                if (property.value.length > (MAX_BYTES + 2L) / 3 * 4) return null
                val data = try {
                    Base64.getDecoder().decode(property.value)
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (data.size > MAX_BYTES) return null
                val sha256 = sha256(data)
                // without an id of its own, the file of inline content gets one from its hash
                AttachmentRef(property, sha256, null, id ?: sha256.take(32), name, mimeType, data.size.toLong(), data)
            } else {
                null
            }
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
