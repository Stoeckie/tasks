package org.tasks.etebase.sim

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.etebase.client.Account
import com.etebase.client.Client
import com.todoroo.astrid.alarms.AlarmCalculator
import com.todoroo.astrid.alarms.AlarmService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.tasks.analytics.Reporting
import org.tasks.caldav.FileStorage
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.data.MergedGeofence
import org.tasks.data.TaskMover
import org.tasks.data.TaskSaver
import org.tasks.data.db.Database
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Place
import org.tasks.data.entity.SYNC_ATTACHMENTS
import org.tasks.data.entity.Task
import org.tasks.data.entity.TaskAttachment
import org.tasks.etebase.EtebaseAttachments
import org.tasks.etebase.EtebaseClient
import org.tasks.etebase.EtebaseClientProvider
import org.tasks.etebase.EtebaseLocalCache
import org.tasks.etebase.EtebaseSynchronizer
import org.tasks.files.AttachmentStore
import org.tasks.filters.CaldavFilter
import org.tasks.location.LocationService
import org.tasks.location.MapPosition
import org.tasks.preferences.AppPreferences
import org.tasks.reminders.Random
import org.tasks.service.TaskDeleter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Attachment files in a directory, as AndroidAttachmentStore keeps them. */
class DirStore(val dir: File) : AttachmentStore {
    /** Files this store wrote for the sync. */
    val written = ArrayList<File>()

    override suspend fun size(uri: String) = file(uri)?.takeIf { it.isFile }?.length()
    override suspend fun read(uri: String, maxBytes: Int) =
        file(uri)?.takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()
    override suspend fun mimeType(uri: String) = "application/octet-stream"
    override suspend fun create(name: String, mimeType: String?, data: ByteArray): String? {
        val safe = AttachmentStore.safeName(name)
        var file = File(dir, safe)
        var n = 1
        while (file.exists()) file = File(dir, "${safe.substringBeforeLast('.')}-${n++}.${safe.substringAfterLast('.', "bin")}")
        file.writeBytes(data)
        written.add(file)
        return file.toURI().toString()
    }

    /** A file the user picked: copied into the attachments directory, as the app does. */
    fun add(name: String, data: ByteArray): String =
        File(dir, "user-${dir.list()!!.size}-${name.filter { it.isLetterOrDigit() }.take(20)}").also { it.writeBytes(data) }
            .toURI().toString()

    private fun file(uri: String): File? = try {
        File(java.net.URI(uri))
    } catch (e: Exception) {
        null
    }
}

/** Requests to a real etebase server. */
class OkHttpTransport(private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()) : Transport {
    override fun exchange(method: String, url: String, token: String?, body: ByteArray?): Pair<Int, ByteArray> {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("ACCEPT", "application/msgpack")
            .apply { token?.let { header("Authorization", "Token $it") } }
            .method(method, body?.toRequestBody("application/msgpack".toMediaType()))
            .build()
        client.newCall(request).execute().use { return it.code to it.body!!.bytes() }
    }
}

/**
 * One phone: Room database, VTODO cache, etebase item cache, attachment files, and the real EtebaseSynchronizer,
 * EtebaseClient, EtebaseAttachments, iCalendar, TaskSaver, TaskDeleter and TaskMover. The user acts on it like the
 * app does (editor, move, duplicate, delete). [restart] keeps the database and the files; [reinstall] starts empty.
 */
class SimDevice(
    val name: String,
    private val transport: Transport,
    private val session: String,
    private val root: File,
    private val trace: (String) -> Unit = {},
    private val limits: org.tasks.etebase.AttachmentLimits = org.tasks.etebase.AttachmentLimits(),
    /** False for a device that keeps no attachment files (desktop, iOS: AttachmentStore.None). */
    private val keepsFiles: Boolean = true,
) {
    /** Bytes moved by this installation (kept across restarts). */
    var counters = org.tasks.etebase.sim.Counters()
        private set
    var life = 0
        private set
    lateinit var http: SimHttp
        private set
    lateinit var db: Database
        private set
    lateinit var store: DirStore
        private set
    private lateinit var dir: File
    private lateinit var parts: Parts
    var collectionFilter: ((String) -> Boolean)? = null
    /** Network settings that survive restarts (see SimHttp). */
    var readOnly: Set<String> = emptySet()
    var proxyLimit: Int? = null
    val exceptions = ArrayList<Throwable>()

    val caldavDao get() = db.caldavDao()
    val taskDao get() = db.taskDao()
    val dirtyDao get() = db.dirtyDao()
    val attachmentDao get() = db.taskAttachmentDao()
    val attachments get() = parts.attachments
    val taskDeleter get() = parts.taskDeleter
    val vtodoCache get() = parts.vtodoCache
    val cacheDir get() = File(dir, "files")
    val home get() = dir
    val accountUuid = "acc-$name"

    init {
        reinstall()
    }

    /** A new installation: empty database and files, the account logged in again. */
    fun reinstall() {
        if (::db.isInitialized) close()
        life++
        counters = Counters()
        dir = File(root, "$name-$life").apply { mkdirs() }
        File(dir, "attachments").mkdirs()
        db = Room.inMemoryDatabaseBuilder<Database>()
            .setDriver(BundledSQLiteDriver())
            .addCallback(Database.CALLBACK)
            .build()
        store = DirStore(File(dir, "attachments"))
        runBlocking {
            caldavDao.insert(
                CaldavAccount(
                    uuid = accountUuid, accountType = CaldavAccount.TYPE_ETEBASE, url = "http://sim/", username = name,
                    password = "x",
                )
            )
        }
        restart()
    }

    /** The app starts again: everything is rebuilt from the database and the files. */
    fun restart() {
        val offline = ::http.isInitialized && http.offline
        http = SimHttp(transport, { trace("$name$it") }, counters).also {
            it.offline = offline; it.collectionFilter = collectionFilter; it.readOnly = readOnly; it.proxyLimit = proxyLimit
        }
        parts = Parts()
    }

    fun close() {
        runCatching { runBlocking { EtebaseLocalCache.clear(cacheDir.absolutePath, cacheUser) } }
        db.close()
    }

    private var logins = 0
    private val cacheUser get() = "sim-${root.name}-$name-$life-$logins"

    /** The user removes the account (as the app does: its tasks go) and logs in again; files stay. */
    fun logout() = runBlocking {
        val account = caldavDao.getAccountByUuid(accountUuid)!!
        parts.taskDeleter.delete(account)
        runCatching { EtebaseLocalCache.clear(cacheDir.absolutePath, cacheUser) }
        logins++
        caldavDao.insert(
            CaldavAccount(
                uuid = accountUuid, accountType = CaldavAccount.TYPE_ETEBASE, url = "http://sim/", username = name,
                password = "x",
            )
        )
        restart()
    }

    /** A new subtask of [parent], in its list, as the app creates one. */
    suspend fun newSubtask(parent: Long, title: String): Task? {
        val parentRow = caldavDao.getTask(parent) ?: return null
        val task = Task(title = title, parent = parent)
        taskDao.createNew(task)
        caldavDao.insert(task, CaldavTask(task = task.id, calendar = parentRow.calendar, remoteParent = parentRow.remoteId), false)
        dirtyDao.setDirty(listOf(task.id))
        return task
    }

    private inner class Parts {
        val vtodoCache = VtodoCache(caldavDao, FileStorage(File(dir, "vtodo").absolutePath))
        val preferences: AppPreferences = mock {
            onBlocking { addTasksToTop() } doReturn false
            onBlocking { isDefaultDueTimeEnabled() } doReturn false
            onBlocking { defaultAlarms() } doReturn emptyList()
            onBlocking { defaultDueTime() } doReturn 0
            onBlocking { isCurrentlyQuietHours() } doReturn false
            onBlocking { defaultPriority() } doReturn Task.Priority.NONE
            onBlocking { defaultRingMode() } doReturn 0
            onBlocking { defaultLocationReminder() } doReturn 0
        }
        val locationService = object : LocationService {
            override val locationDao = db.locationDao()
            override val appPreferences = preferences
            override suspend fun currentLocation(): MapPosition? = null
            override fun addGeofences(geofence: MergedGeofence) = Unit
            override fun removeGeofences(place: Place) = Unit
        }
        val taskSaver = TaskSaver(taskDao, mock(), mock(), locationService, mock(), mock(), caldavDao)
        val iCal = iCalendar(
            db.tagDataDao(), preferences, db.locationDao(), mock(), locationService, db.tagDao(), taskDao, dirtyDao,
            taskSaver, caldavDao, db.alarmDao(),
            AlarmService(db.alarmDao(), taskDao, dirtyDao, mock(), mock(), AlarmCalculator(Random()), preferences),
            vtodoCache, mock(),
        )
        val attachments = EtebaseAttachments(attachmentDao, if (keepsFiles) store else AttachmentStore.None, limits)
        val taskDeleter = TaskDeleter(db.deletionDao(), taskDao, caldavDao, mock(), vtodoCache, mock(), mock())
        val taskMover = TaskMover(taskDao, caldavDao, db.googleTaskDao(), dirtyDao, preferences, mock(), taskDeleter)
        val client: EtebaseClient by lazy {
            val etebase = Account.restore(Client.client_new_with_impl("http://sim/", http), session, null)
            EtebaseClient(cacheDir.absolutePath, cacheUser, etebase, caldavDao)
        }
        val synchronizer = EtebaseSynchronizer(
            caldavDao, dirtyDao, mock(), taskDeleter,
            mock<EtebaseClientProvider> { onBlocking { forAccount(any()) } doReturn client },
            iCal, vtodoCache,
            object : Reporting {
                override fun logEvent(event: String, vararg params: Pair<String, Any>) = Unit
                override fun addTask(source: String) = Unit
                override fun completeTask(source: String) = Unit
                override fun setSku(sku: String?) = Unit
                override val tasksPreferences: org.tasks.preferences.TasksPreferences get() = mock()
                override val analyticsEnabled = false
                override fun reportException(t: Throwable, fatal: Boolean) {
                    exceptions.add(t)
                }
            },
            attachments,
        )
    }

    /** Result of a sync: the account error ("" for none), and whether the app was killed during it. */
    class SyncResult(val error: String, val killed: Boolean)

    /** Syncs as the app does; a kill fault cancels the sync and restarts the app. */
    fun sync(): SyncResult = runBlocking {
        val account = caldavDao.getAccountByUuid(accountUuid)!!
        val job = async(Dispatchers.Default) { parts.synchronizer.sync(account, hasPro = true) }
        http.onKill = { job.cancel(CancellationException("killed")) }
        val killed = try {
            job.await()
            false
        } catch (e: CancellationException) {
            true
        }
        if (killed || http.killed) {
            restart()
            SyncResult("killed", true)
        } else {
            SyncResult(caldavDao.getAccountByUuid(accountUuid)!!.error.orEmpty(), false)
        }
    }

    // the user, as the app does it

    suspend fun calendar(url: String): CaldavCalendar = caldavDao.getCalendarByUrl(accountUuid, url)!!

    suspend fun calendars(): List<CaldavCalendar> = caldavDao.getCalendarsByAccount(accountUuid)

    suspend fun newTask(url: String, title: String): Task {
        val task = Task(title = title)
        taskDao.createNew(task)
        caldavDao.insert(task, CaldavTask(task = task.id, calendar = calendar(url).uuid), false)
        dirtyDao.setDirty(listOf(task.id))
        return task
    }

    suspend fun edit(task: Long, block: Task.() -> Unit) {
        val current = taskDao.fetch(task) ?: return
        val original = current.copy()
        current.block()
        parts.taskSaver.save(current, original)
    }

    /** The editor adds a file the user picked. */
    suspend fun attach(task: Long, name: String, data: ByteArray): TaskAttachment {
        val file = TaskAttachment(name = name, uri = store.add(name, data))
        attachmentDao.insert(file)
        val saved = attachmentDao.getAttachment(file.remoteId)!!
        attachmentDao.insert(listOf(Attachment(task = task, fileId = saved.id!!, attachmentUid = saved.remoteId)))
        attachments.added(task, listOf(saved))
        saveAttachmentChange(task)
        return saved
    }

    /** The editor removes an attachment of a task. */
    suspend fun detach(task: Long, fileId: String) {
        val file = attachmentDao.getAttachments(task).firstOrNull { it.remoteId == fileId } ?: return
        attachmentDao.delete(task, listOf(fileId))
        attachments.removed(task, listOf(file))
        saveAttachmentChange(task)
    }

    private suspend fun saveAttachmentChange(task: Long) {
        val current = taskDao.fetch(task) ?: return
        val original = current.copy()
        current.putTransitory(SYNC_ATTACHMENTS, true)
        parts.taskSaver.save(current, original)
    }

    suspend fun delete(task: Long) {
        parts.taskDeleter.markDeleted(listOf(task))
    }

    suspend fun move(task: Long, url: String) {
        val account = caldavDao.getAccountByUuid(accountUuid)!!
        parts.taskMover.move(listOf(task), CaldavFilter(calendar = calendar(url), account = account))
    }

    /** As TaskDuplicator.clone (app module) for a task without subtasks, tags, places or alarms. */
    suspend fun duplicate(task: Long): Task? {
        val original = taskDao.fetch(task) ?: return null
        val caldavTask = caldavDao.getTask(task) ?: return null
        val clone = original.copy(
            id = Task.NO_ID, creationDate = org.tasks.time.DateTimeUtils2.currentTimeMillis(),
            modificationDate = org.tasks.time.DateTimeUtils2.currentTimeMillis(), reminderLast = 0,
            reminderDismissed = 0, completionDate = 0L, calendarURI = "", remoteId = Task.NO_UUID,
        )
        clone.suppressSync()
        clone.suppressRefresh()
        taskDao.createNew(clone)
        caldavDao.insert(clone, CaldavTask(task = clone.id, calendar = caldavTask.calendar), false)
        attachmentDao.getAttachmentsForTask(task)
            .map { Attachment(task = clone.id, fileId = it.fileId, attachmentUid = it.attachmentUid) }
            .let { attachmentDao.insert(it) }
        parts.taskSaver.save(clone, null)
        return clone
    }

    // what the device has

    /** Live tasks by list url. */
    suspend fun tasks(): List<Pair<Task, CaldavTask>> =
        taskDao.getAll().mapNotNull { t ->
            val task = taskDao.fetch(t.id)?.takeIf { !it.isDeleted } ?: return@mapNotNull null
            caldavDao.getTask(task.id)?.let { task to it }
        }

    /** Linked attachments of [task]: file id to content (null: not on this device). */
    suspend fun files(task: Long): Map<String, ByteArray?> =
        attachmentDao.getAttachments(task).associate { it.remoteId to store.read(it.uri, Int.MAX_VALUE) }

    @Suppress("unused")
    private fun io(e: IOException) = e
}
