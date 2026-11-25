package org.tasks.files

import android.content.ContentResolver
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.common.io.ByteStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tasks.preferences.Preferences
import timber.log.Timber
import java.io.File

/** Attachment files in the attachments directory of the preferences (a `file:` directory or a document tree). */
class AndroidAttachmentStore(
    private val context: Context,
    private val preferences: Preferences,
) : AttachmentStore {

    override suspend fun size(uri: String): Long? = withContext(Dispatchers.IO) {
        try {
            val parsed = uri.toUri()
            when (parsed.scheme) {
                ContentResolver.SCHEME_FILE -> File(parsed.path!!).takeIf { it.isFile }?.length()
                else -> DocumentFile.fromSingleUri(context, parsed)?.takeIf { it.isFile }?.length()
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun read(uri: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri.toUri())?.use { input ->
                ByteStreams.toByteArray(ByteStreams.limit(input, maxBytes + 1L)).takeIf { it.size <= maxBytes }
            }
        } catch (e: Exception) {
            Timber.w("Cannot read attachment: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun mimeType(uri: String): String? =
        try {
            FileHelper.getMimeType(context, uri.toUri())
        } catch (e: Exception) {
            null
        }

    override suspend fun create(name: String, mimeType: String?, data: ByteArray): String? {
        val directory = preferences.attachmentsDirectory ?: return null
        // no path separator or leading dot from another device ever reaches the file system
        val safe = name.replace(UNSAFE, "_").trim().trimStart('.').take(100).ifEmpty { "attachment" }
        val baseName = safe.substringBeforeLast('.').ifEmpty { "attachment" }
        val extension = safe.substringAfterLast('.', "").ifEmpty {
            mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
        }
        val uri = try {
            FileHelper.newFile(context, directory, mimeType, baseName, extension)
        } catch (e: Exception) {
            Timber.w("Cannot create attachment: ${e.javaClass.simpleName}")
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
            }
            uri.toString()
        } catch (e: Exception) {
            Timber.w("Cannot write attachment: ${e.javaClass.simpleName}")
            FileHelper.delete(context, uri)
            null
        }
    }

    companion object {
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    }
}
