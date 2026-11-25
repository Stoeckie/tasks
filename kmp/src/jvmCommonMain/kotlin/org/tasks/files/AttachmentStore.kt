package org.tasks.files

/** Attachment files on this device, for sync. */
interface AttachmentStore {
    /** False where attachments are not kept at all: nothing is downloaded there. */
    val keepsFiles: Boolean get() = true

    /** Size of the file at [uri], or null when it cannot be read. */
    suspend fun size(uri: String): Long?

    /** Content of the file at [uri], or null when it cannot be read or is larger than [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Int): ByteArray?

    /** MIME type of the file at [uri], if known. */
    suspend fun mimeType(uri: String): String?

    /**
     * Writes [data] to a new file in the attachments directory, named after [name] (never outside that directory),
     * and returns its uri, or null when that is not possible.
     */
    suspend fun create(name: String, mimeType: String?, data: ByteArray): String?

    /** Platforms that keep no attachment files: references stay on the server untouched. */
    object None : AttachmentStore {
        override val keepsFiles = false
        override suspend fun size(uri: String): Long? = null
        override suspend fun read(uri: String, maxBytes: Int): ByteArray? = null
        override suspend fun mimeType(uri: String): String? = null
        override suspend fun create(name: String, mimeType: String?, data: ByteArray): String? = null
    }

    companion object {
        // path separators, characters Windows and SAF reject, control and format characters (such as U+202E)
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\p{Cf}]")

        /** [name] from another device as a file name: no path, no leading dot, at most 100 characters. */
        fun safeName(name: String): String =
            name.replace(UNSAFE, "_").trim().trimStart('.').take(100).ifEmpty { "attachment" }
    }
}
