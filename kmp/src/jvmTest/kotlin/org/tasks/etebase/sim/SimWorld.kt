package org.tasks.etebase.sim

import com.etebase.client.Account
import com.etebase.client.Client
import com.etebase.client.FetchOptions
import com.etebase.client.ItemManager
import com.etebase.client.ItemMetadata
import com.etebase.client.User
import org.tasks.caldav.iCalendar.Companion.fromVtodo
import org.tasks.etebase.AttachmentRef
import org.tasks.etebase.EtebaseAttachments
import org.tasks.etebase.EtebaseAttachments.Companion.fromItemContent
import org.tasks.icalendar.VTodo
import java.io.File
import java.nio.file.Files

/** What the server holds, decrypted. */
class ServerView(
    /** Collection uid to live task items (item uid to VTODO). */
    val tasks: Map<String, Map<String, VTodo>>,
    /** Collection uid to attachment items (item uid to file content, null when deleted or unreadable). */
    val attachments: Map<String, Map<String, ByteArray?>>,
) {
    fun refs(vtodo: VTodo): List<AttachmentRef> = EtebaseAttachments.parse(vtodo)
}

/**
 * A server (the in-memory fake, or a real one given by [liveUrl] with [liveUser] and [livePassword]), one account,
 * and its devices. All devices of a world use the same account, as one user with several phones; each restores the
 * session with its own network ([SimHttp]).
 */
class SimWorld(
    liveUrl: String? = null,
    liveUser: String? = null,
    livePassword: String? = null,
    private val trace: (String) -> Unit = {},
    private val limits: org.tasks.etebase.AttachmentLimits = org.tasks.etebase.AttachmentLimits(),
) : AutoCloseable {
    val transport: Transport = if (liveUrl != null) OkHttpTransport() else FakeEtebaseServer()
    private val url = liveUrl?.trimEnd('/')?.plus("/") ?: "http://sim/"
    val root: File = Files.createTempDirectory("sim").toFile()
    private val account: Account
    val session: String
    val collections = ArrayList<String>()
    val devices = ArrayList<SimDevice>()

    init {
        val client = Client.client_new_with_impl(url, SimHttp(transport))
        account = if (liveUrl != null) {
            Account.login(client, liveUser!!, livePassword!!)
        } else {
            Account.signupKey(client, User("sim", "sim@example.com"), ByteArray(32) { it.toByte() })
        }
        session = account.save(null)
    }

    val isLive = liveUrl != null

    fun device(name: String, keepsFiles: Boolean = true) = SimDevice(name, transport, session, root, trace, limits, keepsFiles).also { dev ->
        if (isLive) dev.collectionFilter = { it in collections }
        dev.restart()
        devices.add(dev)
    }

    fun newCollection(name: String): String {
        val manager = account.collectionManager
        val collection = manager.create("etebase.vtodo", ItemMetadata().apply { this.name = name }, "")
        manager.upload(collection)
        collections.add(collection.uid)
        return collection.uid
    }

    /** The collections as another client writes them. */
    val collectionManager get() = account.collectionManager

    /** Items of collection [uid] as another client writes them. */
    fun itemManager(uid: String): ItemManager = account.collectionManager.let { it.getItemManager(it.fetch(uid, null)) }

    /** Everything in the collections of this world, read without faults. */
    fun view(): ServerView {
        val tasks = LinkedHashMap<String, Map<String, VTodo>>()
        val attachments = LinkedHashMap<String, Map<String, ByteArray?>>()
        val manager = account.collectionManager
        for (uid in collections) {
            val collection = manager.fetch(uid, null)
            if (collection.isDeleted) continue
            val items = manager.getItemManager(collection)
            val t = LinkedHashMap<String, VTodo>()
            val a = LinkedHashMap<String, ByteArray?>()
            var stoken: String? = null
            do {
                val page = items.list(FetchOptions().stoken(stoken).limit(100))
                for (item in page.data) {
                    if (item.meta.itemType == EtebaseAttachments.ITEM_TYPE) {
                        a[item.uid] = if (item.isDeleted) null else runCatching { fromItemContent(item.content) }.getOrNull()
                    } else if (!item.isDeleted) {
                        fromVtodo(item.contentString)?.let { t[item.uid] = it }
                    }
                }
                stoken = page.stoken
            } while (!page.isDone)
            tasks[uid] = t
            attachments[uid] = a
        }
        return ServerView(tasks, attachments)
    }

    override fun close() {
        devices.forEach { runCatching { it.close() } }
        if (isLive) {
            val manager = account.collectionManager
            collections.forEach { uid ->
                runCatching { manager.fetch(uid, null).apply { delete() }.let { manager.upload(it) } }
            }
        }
        root.deleteRecursively()
    }
}
