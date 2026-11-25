package org.tasks.etebase.sim

import org.tasks.files.AttachmentStore

/** Attachment files in memory. */
class MemStore : AttachmentStore {
    val files = linkedMapOf<String, ByteArray>()
    override suspend fun size(uri: String) = files[uri]?.size?.toLong()
    override suspend fun read(uri: String, maxBytes: Int) = files[uri]?.takeIf { it.size <= maxBytes }
    override suspend fun mimeType(uri: String) = "application/octet-stream"
    override suspend fun create(name: String, mimeType: String?, data: ByteArray) =
        "mem:///${files.size}/$name".also { files[it] = data }
}
