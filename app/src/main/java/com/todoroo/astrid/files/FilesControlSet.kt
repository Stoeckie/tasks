/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tasks.R
import org.tasks.Strings
import org.tasks.compose.edit.AttachmentRow
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.entity.TaskAttachment
import org.tasks.dialogs.AddAttachmentDialog
import org.tasks.etebase.EtebaseAttachments
import org.tasks.etebase.EtebaseAttachments.Companion.Problem
import org.tasks.extensions.Context.takePersistableUriPermission
import org.tasks.files.AttachmentStore
import org.tasks.files.FileHelper
import org.tasks.filters.CaldavFilter
import org.tasks.preferences.Preferences
import org.tasks.ui.TaskEditControlFragment
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class FilesControlSet : TaskEditControlFragment() {
    @Inject lateinit var taskAttachmentDao: TaskAttachmentDao
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var attachmentStore: AttachmentStore

    @Composable
    override fun Content() {
        val viewState = viewModel.viewState.collectAsStateWithLifecycle().value
        LaunchedEffect(Unit) {
            if (viewState.task.hasTransitory(TaskAttachment.KEY)) {
                for (uri in (viewState.task.getTransitory<ArrayList<Uri>>(TaskAttachment.KEY))!!) {
                    newAttachment(uri)
                }
            }
        }
        val context = LocalContext.current
        val problems by produceState(emptyMap(), viewState.attachments, viewState.list) {
            value = problems(viewState.list, viewState.attachments)
        }
        AttachmentRow(
            attachments = viewState.attachments,
            problems = problems,
            openAttachment = {
                Timber.d("Clicked open $it")
                FileHelper.startActionView(
                    context,
                    if (Strings.isNullOrEmpty(it.uri)) null else Uri.parse(it.uri)
                )
            },
            deleteAttachment = {
                Timber.d("Clicked delete $it")
                viewModel.setAttachments(viewState.attachments - it)
            },
            addAttachment = {
                Timber.d("Add attachment clicked")
                AddAttachmentDialog.newAddAttachmentDialog(this@FilesControlSet)
                    .show(parentFragmentManager, FRAG_TAG_ADD_ATTACHMENT_DIALOG)
            },
        )
    }

    /** Why EteSync does not synchronize an attachment, by its uuid (same limits as the sync). */
    private suspend fun problems(list: CaldavFilter, attachments: Collection<TaskAttachment>): Map<String, String> {
        if (!list.account.isEtebaseAccount) return emptyMap()
        var count = 0
        var bytes = 0L
        return attachments.mapNotNull { attachment ->
            val size = attachmentStore.size(attachment.uri) ?: return@mapNotNull null
            when (EtebaseAttachments.problem(size, count, bytes)) {
                null -> {
                    count++
                    bytes += size
                    null
                }
                Problem.TOO_LARGE ->
                    attachment.remoteId to getString(R.string.attachment_not_synced_too_large, MIB_LIMIT)
                Problem.TASK_LIMIT -> attachment.remoteId to getString(
                    R.string.attachment_not_synced_task_limit,
                    EtebaseAttachments.MAX_PER_TASK,
                    (EtebaseAttachments.MAX_TASK_BYTES / MIB).toInt(),
                )
            }
        }.toMap()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == AddAttachmentDialog.REQUEST_CAMERA || requestCode == AddAttachmentDialog.REQUEST_AUDIO) {
            if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val uri = data!!.data
                    copyToAttachmentDirectory(uri)
                    FileHelper.delete(requireContext(), uri)
                }
            }
        } else if (requestCode == AddAttachmentDialog.REQUEST_STORAGE || requestCode == AddAttachmentDialog.REQUEST_GALLERY) {
            if (resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    val clip = data!!.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val item = clip.getItemAt(i)
                            requireContext().takePersistableUriPermission(item.uri)
                            copyToAttachmentDirectory(item.uri)
                        }
                    } else {
                        requireContext().takePersistableUriPermission(data.data!!)
                        copyToAttachmentDirectory(data.data)
                    }
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private suspend fun copyToAttachmentDirectory(input: Uri?) {
        val destination = preferences.attachmentsDirectory ?: return
        newAttachment(
            if (FileHelper.isInTree(requireContext(), destination, input!!)) {
                Timber.d("$input already exists in $destination")
                input
            } else {
                Timber.d("Copying $input to $destination")
                FileHelper.copyToUri(requireContext(), destination, input)
            }
        )
    }

    private suspend fun newAttachment(output: Uri) {
        val attachment = TaskAttachment(
            uri = output.toString(),
            name = FileHelper.getFilename(requireContext(), output)!!,
        )
        withContext(Dispatchers.IO) {
            taskAttachmentDao.insert(attachment)
            viewModel.setAttachments(
                viewModel.viewState.value.attachments +
                        (taskAttachmentDao.getAttachment(attachment.remoteId) ?: return@withContext))
        }
    }

    companion object {
        val TAG = R.string.TEA_ctrl_files_pref
        private const val FRAG_TAG_ADD_ATTACHMENT_DIALOG = "frag_tag_add_attachment_dialog"
        private const val MIB = 1024 * 1024
        private const val MIB_LIMIT = EtebaseAttachments.MAX_BYTES / MIB
    }
}
