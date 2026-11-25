package org.tasks.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.MetadataTombstone
import org.tasks.data.entity.TaskAttachment

@Dao
interface TaskAttachmentDao {
    @Query("SELECT * FROM attachment WHERE task = :task")
    suspend fun getAttachmentsForTask(task: Long): List<Attachment>

    @Query("SELECT attachment_file.* FROM attachment_file INNER JOIN attachment ON attachment_file.file_uuid = attachment.file_uuid WHERE task = :task")
    suspend fun getAttachments(task: Long): List<TaskAttachment>

    @Query("SELECT * FROM attachment_file")
    suspend fun getAttachments(): List<TaskAttachment>

    @Query("SELECT * FROM attachment_file WHERE file_uuid = :remoteId")
    suspend fun getAttachment(remoteId: String): TaskAttachment?

    @Query("DELETE FROM attachment WHERE task = :taskId AND file_uuid = :attachment")
    suspend fun delete(taskId: Long, attachment: String)

    @Query("DELETE FROM attachment WHERE task = :taskId AND file_uuid IN (:attachments)")
    suspend fun delete(taskId: Long, attachments: List<String>)

    @Insert
    suspend fun insert(attachments: List<Attachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: TaskAttachment)

    @Update
    suspend fun update(attachment: TaskAttachment)

    @Delete
    suspend fun delete(value: List<TaskAttachment>)

    /** EteSync records in metadata_tombstone: [REMOVED] and [SYNCED]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: MetadataTombstone)

    // EteSync: attachments that the user removed from a task on this device, until the removal is pushed
    // (metadata_tombstone, key "<task>/<file id>")

    @Query("SELECT substr(entity_key, length(:task) + 2) FROM metadata_tombstone WHERE category = '$REMOVED' AND entity_key LIKE :task || '/%'")
    suspend fun getRemoved(task: Long): List<String>

    @Query("DELETE FROM metadata_tombstone WHERE category = '$REMOVED' AND entity_key = :task || '/' || :file")
    suspend fun deleteRemoved(task: Long, file: String)

    @Query("DELETE FROM metadata_tombstone WHERE category = '$REMOVED' AND entity_key LIKE :task || '/%'")
    suspend fun deleteRemoved(task: Long)

    @Query("SELECT entity_key FROM metadata_tombstone WHERE category = '$REMOVED'")
    suspend fun getAllRemoved(): List<String>

    // EteSync: files that sync wrote on this device (metadata_tombstone, key "<file id>/<uri>"), deleted once unused

    @Query("SELECT entity_key FROM metadata_tombstone WHERE category = '$SYNCED'")
    suspend fun getSynced(): List<String>

    @Query("DELETE FROM metadata_tombstone WHERE category = '$SYNCED' AND entity_key = :key")
    suspend fun deleteSynced(key: String)

    @Query("SELECT DISTINCT file_uuid FROM attachment")
    suspend fun getLinked(): List<String>

    /** Deletes the file row [id] if it still names [uri] and no task links it; returns whether it did. */
    @Query("DELETE FROM attachment_file WHERE file_uuid = :id AND uri = :uri AND NOT EXISTS (SELECT 1 FROM attachment WHERE attachment.file_uuid = :id)")
    suspend fun deleteUnlinked(id: String, uri: String): Int

    companion object {
        const val REMOVED = "etebase_attachment_removed"
        const val SYNCED = "etebase_attachment_synced"
    }
}
