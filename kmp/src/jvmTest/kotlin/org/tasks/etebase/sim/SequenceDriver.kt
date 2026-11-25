package org.tasks.etebase.sim

import kotlinx.coroutines.runBlocking
import org.tasks.etebase.AttachmentLimits
import org.tasks.etebase.EtebaseAttachments
import org.tasks.etebase.EtebaseAttachments.Companion.sha256
import org.tasks.time.DateTimeUtils2
import java.io.File
import kotlin.random.Random

/** Where in a sync a fault or a concurrent action happens: the [nth] request of a kind. */
enum class Where(val matches: (Request) -> Boolean) {
    ANY({ true }),
    LIST({ it.isList }),
    DOWNLOAD({ it.isItemGet }),
    UPLOAD({ it.isTransaction }),
    BIG_UPLOAD({ it.isTransaction && (it.body?.size ?: 0) > 4000 }),
}

/** One step of a sequence. Tasks, lists and files are picked by index among what the device has when it runs. */
sealed class Step {
    abstract val dev: Int

    data class Create(override val dev: Int, val list: Int) : Step()
    data class Edit(override val dev: Int, val task: Int, val field: Int, val value: Int) : Step()
    data class Attach(override val dev: Int, val task: Int, val size: Int, val name: Int, val oversize: Boolean) : Step()
    data class Detach(override val dev: Int, val task: Int, val file: Int) : Step()
    data class Delete(override val dev: Int, val task: Int) : Step()
    data class Move(override val dev: Int, val task: Int, val list: Int) : Step()
    data class Duplicate(override val dev: Int, val task: Int) : Step()
    data class Network(override val dev: Int, val online: Boolean) : Step()
    data class Sync(override val dev: Int) : Step()
    data class FaultySync(override val dev: Int, val kind: FaultKind, val where: Where, val nth: Int) : Step()

    /** While [dev] syncs, before its [nth] request of a kind: [action] by device [other], which then syncs (unless it is [dev]). */
    data class BusySync(override val dev: Int, val where: Where, val nth: Int, val other: Int, val action: Step) : Step()
    data class Restart(override val dev: Int) : Step()
    data class Reinstall(override val dev: Int) : Step()
}

/**
 * Model-based test of EteSync sync: random interleavings of user actions, syncs, faults and restarts on simulated
 * devices (2 or 3, one account, two lists), checked after every step and at quiescence:
 * - no exception escapes a sync, a sync without faults has no error, at quiescence nothing is left to push;
 * - all devices converge to the same tasks, fields and attachments, and the server holds the same;
 * - no attachment disappears unless a user removed it or deleted its task, and a removed one never comes back;
 * - attachment bytes travel only for new attachments: each installation downloads each file at most once and uploads
 *   it at most once, plus once per move and per duplicate it made of a task with it;
 * - files are written only into the attachments directory, within the limits.
 */
class SequenceDriver(
    private val live: Triple<String, String, String>? = null,
    private val limits: AttachmentLimits = AttachmentLimits(maxBytes = 48 * 1024, maxPerTask = 10, maxTaskBytes = 400 * 1024),
    private val trace: Boolean = false,
) {
    class Failure(message: String) : AssertionError(message)

    fun generate(seed: Long, length: Int? = null): Pair<Int, List<Step>> {
        val random = Random(seed)
        val devices = if (random.nextInt(3) == 0) 3 else 2
        val n = length ?: (30 + random.nextInt(31))
        return devices to List(n) { step(random, devices, nested = false) }
    }

    private fun step(r: Random, devices: Int, nested: Boolean): Step {
        val dev = r.nextInt(devices)
        val weights = if (nested) NESTED else WEIGHTS
        var pick = r.nextInt(weights.sum())
        val kind = weights.indexOfFirst { w -> (pick < w).also { if (!it) pick -= w } }
        return when (kind) {
            0 -> Step.Create(dev, r.nextInt(2))
            1 -> Step.Edit(dev, r.nextInt(100), r.nextInt(3), r.nextInt(5))
            2 -> Step.Attach(dev, r.nextInt(100), sizeOf(r), r.nextInt(NAMES.size), r.nextInt(25) == 0)
            3 -> Step.Detach(dev, r.nextInt(100), r.nextInt(100))
            4 -> Step.Delete(dev, r.nextInt(100))
            5 -> Step.Move(dev, r.nextInt(100), r.nextInt(2))
            6 -> Step.Duplicate(dev, r.nextInt(100))
            7 -> Step.Network(dev, r.nextInt(3) != 0)
            8 -> Step.Sync(dev)
            9 -> FaultKind.entries.random(r).let { kind ->
                // a proxy refuses large uploads only
                val where = if (kind == FaultKind.REFUSE) listOf(Where.UPLOAD, Where.BIG_UPLOAD).random(r) else Where.entries.random(r)
                Step.FaultySync(dev, kind, where, 1 + r.nextInt(3))
            }
            10 -> Step.BusySync(dev, Where.entries.random(r), 1 + r.nextInt(3), r.nextInt(devices), step(r, devices, nested = true))
            11 -> Step.Restart(dev)
            else -> Step.Reinstall(dev)
        }
    }

    private fun sizeOf(r: Random) = when (r.nextInt(10)) {
        0 -> 0
        1, 2 -> limits.maxBytes - r.nextInt(2000)
        else -> 1 + r.nextInt(6000)
    }

    /** Runs a sequence; returns null, or what failed. */
    fun run(devices: Int, steps: List<Step>, seed: Long = 0, log: (String) -> Unit = {}): String? {
        val run = Run(devices, seed, log)
        return try {
            run.execute(steps)
            null
        } catch (e: Failure) {
            e.message
        } catch (e: Throwable) {
            "harness: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(12).joinToString("\n") { "    at $it" }
        } finally {
            run.close()
            // the inline mock maker keeps every mock (a few per device start) until cleared, and the native objects
            // of the library are freed only when their Java objects are collected
            org.mockito.Mockito.framework().clearInlineMocks()
            System.gc()
        }
    }

    /** Removes steps while the same kind of failure remains. */
    fun shrink(devices: Int, steps: List<Step>, failure: String, budget: Int = 300): Pair<List<Step>, String> {
        val kind = failure.substringBefore(':')
        var current = steps
        var message = failure
        var runs = 0
        var chunk = maxOf(1, current.size / 2)
        while (chunk >= 1 && runs < budget) {
            var i = 0
            var removedAny = false
            while (i < current.size && runs < budget) {
                val candidate = current.take(i) + current.drop(i + chunk)
                runs++
                val result = run(devices, candidate)
                if (result != null && result.substringBefore(':') == kind) {
                    current = candidate
                    message = result
                    removedAny = true
                } else {
                    i += chunk
                }
            }
            if (!removedAny) chunk /= 2
        }
        // nested actions too: a concurrent action replaced by a plain sync
        current = current.mapIndexed { index, step ->
            if (step !is Step.BusySync || runs >= budget) return@mapIndexed step
            val candidate = current.toMutableList().apply { this[index] = Step.Sync(step.dev) }
            runs++
            val result = run(devices, candidate)
            if (result != null && result.substringBefore(':') == kind) {
                message = result
                Step.Sync(step.dev)
            } else {
                step
            }
        }
        return current to message
    }

    private inner class Run(private val n: Int, seed: Long, private val log: (String) -> Unit) : AutoCloseable {
        private val world = SimWorld(
            liveUrl = live?.first, liveUser = live?.second, livePassword = live?.third, limits = limits,
            trace = { if (trace) log(it) },
        )
        private val lists = listOf(world.newCollection("L1-$seed"), world.newCollection("L2-$seed"))
        private val devices = List(n) { world.device("D$it") }
        private var clock = 1_800_000_000_000L
        private var titles = 0
        private var files = 0

        // the model: what the users did
        private val added = HashMap<String, MutableSet<String>>() // title to file ids added to it
        private val removed = HashMap<String, MutableSet<String>>() // title to file ids removed from it
        private val deleted = HashSet<String>() // titles a user deleted
        private val content = HashMap<String, ByteArray>() // file id to content
        private val oversize = HashSet<String>()
        private val extra = HashMap<String, Int>() // "<device>/<life>/<file id>" to uploads allowed by moves and duplicates
        private val movers = HashMap<String, MutableSet<Int>>() // title to devices that moved it
        private val onServer = HashMap<String, MutableSet<String>>() // title to file ids seen on the server for it
        private val gone = HashMap<String, MutableSet<String>>() // title to removed file ids whose removal reached the server
        private val fileOf = HashMap<String, String>() // attachment item uid to file id
        // files a device had when the reply of one of its uploads was lost: it cannot tell a removal elsewhere from its
        // own new attachment, and keeps it (documented: such a removal can come back once)
        private val ambiguous = HashSet<String>()
        private var stepNo = 0

        init {
            tick()
            devices.forEach { dev -> sync(dev, clean = true) }
        }

        private fun tick() {
            clock += 61_000
            DateTimeUtils2.setCurrentMillisFixed(clock)
        }

        private fun fail(kind: String, detail: String): Nothing {
            if (trace) runCatching { dump() }
            throw Failure("$kind: $detail (step $stepNo)")
        }

        private fun dump() = runBlocking {
            val view = world.view()
            for ((list, tasks) in view.tasks) {
                log("server ${list.take(4)}: " + tasks.map { (uid, v) -> "${v.summary}@${uid.take(4)} uid=${v.uid?.take(6)} refs=${view.refs(v).map { "${it.id.takeLast(4)}>${it.uid.take(4)}" }}" })
                log("server ${list.take(4)} attachments: " + view.attachments[list].orEmpty().map { (uid, c) -> "${uid.take(4)}:${c?.size}" })
            }
            for (dev in devices) {
                for (t in dev.taskDao.getAll()) {
                    val task = dev.taskDao.fetch(t.id) ?: continue
                    val rows = dev.caldavDao.getTasks(t.id).map { r ->
                        "${dev.caldavDao.getCalendar(r.calendar!!)?.url?.take(4)} rid=${r.remoteId?.take(6)} obj=${r.obj?.take(4)} moved=${r.isDeleted()} dirty=${dev.dirtyDao.isDirty(r.id)}"
                    }
                    val links = dev.attachmentDao.getAttachments(t.id).map { "${it.remoteId.takeLast(4)}${if (it.uri.isEmpty()) "(placeholder)" else ""}" }
                    log("${dev.name} task ${task.title} deleted=${task.isDeleted} rows=$rows links=$links removed=${dev.attachmentDao.getRemoved(t.id).map { it.takeLast(4) }}")
                }
            }
        }

        fun execute(steps: List<Step>) {
            for (step in steps) {
                stepNo++
                tick()
                log("step $stepNo: $step")
                apply(step)
                if (trace) dump()
                checkStep()
            }
            quiesce()
            checkFinal()
        }

        override fun close() {
            DateTimeUtils2.setCurrentMillisSystem()
            world.close()
        }

        private fun sync(dev: SimDevice, clean: Boolean) {
            val result = dev.sync()
            if (dev.exceptions.isNotEmpty()) {
                fail("exception", "${dev.name}: ${dev.exceptions.first()}\n" + dev.exceptions.first().stackTrace.take(10).joinToString("\n") { "    at $it" })
            }
            if (clean && !dev.http.offline && result.error.isNotEmpty()) fail("sync error", "${dev.name}: ${result.error}")
            log("  ${dev.name} synced: ${result.error.ifEmpty { "ok" }}")
            if (trace) dump()
        }

        private suspend fun pickTask(dev: SimDevice, index: Int) =
            dev.tasks().sortedBy { it.first.title }.let { if (it.isEmpty()) null else it[index % it.size] }

        private fun apply(step: Step): Unit = runBlocking {
            val dev = devices[step.dev]
            when (step) {
                is Step.Create -> {
                    if (dev.caldavDao.getCalendarByUrl(dev.accountUuid, lists[step.list]) == null) return@runBlocking
                    val title = "T${++titles}"
                    dev.newTask(lists[step.list], title)
                    added[title] = HashSet()
                }
                is Step.Edit -> pickTask(dev, step.task)?.let { (task, _) ->
                    dev.edit(task.id) {
                        when (step.field) {
                            0 -> notes = "n${step.value}"
                            1 -> priority = step.value % 4
                            else -> completionDate = if (completionDate > 0) 0 else clock
                        }
                    }
                }
                is Step.Attach -> pickTask(dev, step.task)?.let { (task, _) ->
                    val title = task.title!!
                    if (!step.oversize && (added[title].orEmpty() - removed[title].orEmpty()).size >= limits.maxPerTask - 2) return@let
                    val size = if (step.oversize) limits.maxBytes + 1 + step.size % 100 else step.size
                    val data = Random(++files * 7919L).nextBytes(size)
                    val file = dev.attach(task.id, NAMES[step.name], data)
                    content[file.remoteId] = data
                    added.getOrPut(title) { HashSet() }.add(file.remoteId)
                    if (step.oversize) oversize.add(file.remoteId)
                }
                is Step.Detach -> pickTask(dev, step.task)?.let { (task, _) ->
                    val linked = dev.attachmentDao.getAttachments(task.id).sortedBy { it.remoteId }
                    if (linked.isEmpty()) return@let
                    val file = linked[step.file % linked.size]
                    dev.detach(task.id, file.remoteId)
                    removed.getOrPut(task.title!!) { HashSet() }.add(file.remoteId)
                }
                is Step.Delete -> pickTask(dev, step.task)?.let { (task, _) ->
                    dev.delete(task.id)
                    deleted.add(task.title!!)
                }
                is Step.Move -> pickTask(dev, step.task)?.let { (task, caldavTask) ->
                    val target = lists[step.list]
                    val calendar = dev.caldavDao.getCalendarByUrl(dev.accountUuid, target) ?: return@let
                    if (calendar.uuid == caldavTask.calendar) return@let
                    allow(dev, added[task.title!!].orEmpty())
                    movers.getOrPut(task.title!!) { HashSet() }.add(step.dev)
                    dev.move(task.id, target)
                }
                is Step.Duplicate -> pickTask(dev, step.task)?.let { (task, _) ->
                    val copied = dev.files(task.id).filterValues { it != null }.keys
                    val clone = dev.duplicate(task.id) ?: return@let
                    val title = "T${++titles}"
                    dev.edit(clone.id) { this.title = title }
                    added[title] = copied.toMutableSet()
                    allow(dev, copied)
                }
                is Step.Network -> dev.http.offline = !step.online
                is Step.Sync -> sync(dev, clean = true)
                is Step.FaultySync -> {
                    val lost = dev.counters.lostUploads
                    dev.http.faults.add(nth(step.kind, step.where, step.nth))
                    sync(dev, clean = false)
                    dev.http.faults.clear()
                    if (dev.counters.lostUploads > lost) dev.tasks().forEach { (t, _) -> ambiguous.addAll(dev.files(t.id).keys) }
                }
                is Step.BusySync -> {
                    var seen = 0
                    val other = devices[step.other]
                    dev.http.hooks.add({ r: Request -> step.where.matches(r) && ++seen == step.nth } to {
                        log("  concurrently: ${step.action}")
                        apply(step.action)
                        if (other !== dev) sync(other, clean = true)
                    })
                    sync(dev, clean = false)
                    dev.http.hooks.clear()
                }
                is Step.Restart -> dev.restart()
                is Step.Reinstall -> {
                    // a user who reinstalls loses what was not synced: this tests the sync, so it syncs first
                    val offline = dev.http.offline
                    dev.http.offline = false
                    repeat(3) { if (pending(dev)) sync(dev, clean = true) }
                    if (!pending(dev)) {
                        dev.reinstall()
                        sync(dev, clean = true) // logging in syncs
                    } else {
                        log("  not reinstalled: still something to push")
                    }
                    dev.http.offline = offline
                }
            }
        }

        private fun nth(kind: FaultKind, where: Where, nth: Int): Fault {
            var seen = 0
            return Fault(kind, { where.matches(it) && ++seen == nth }, "$where #$nth")
        }

        /** A move or a duplicate by [dev] may upload these files once more. */
        private fun allow(dev: SimDevice, ids: Collection<String>) = ids.forEach {
            extra.merge("${dev.name}/${dev.life}/$it", 1, Int::plus)
        }

        private fun titleRefs(view: ServerView): Map<String, Set<String>> {
            val result = HashMap<String, MutableSet<String>>()
            for ((_, tasks) in view.tasks) {
                for ((_, vtodo) in tasks) {
                    val refs = view.refs(vtodo)
                    refs.forEach { fileOf[it.uid] = it.id }
                    result.getOrPut(vtodo.summary ?: "") { HashSet() }.addAll(refs.map { it.id })
                }
            }
            return result
        }

        /** Titles of tasks whose move some device has not finished (the old item is still to be deleted). */
        private fun moving(): Set<String> = runBlocking {
            devices.flatMap { dev ->
                dev.calendars().flatMap { dev.caldavDao.getMoved(it.uuid!!) }.mapNotNull { dev.taskDao.fetch(it.task)?.title }
            }.toSet()
        }

        private fun checkStep() {
            val view = world.view()
            val refs = titleRefs(view)
            val moving = moving()
            for ((title, ids) in refs) {
                onServer.getOrPut(title) { HashSet() }.addAll(ids)
                // during a move, an attachment removed meanwhile in the old list can show in the new one until the
                // moving device syncs again
                if (title in moving || title in deleted) continue // (a deleted task that a move kept: as the mover had it)
                gone[title]?.firstOrNull { it in ids && it !in ambiguous }?.let { fail("came back", "removed $it of $title is referenced again") }
            }
            for ((title, ids) in onServer) {
                val now = refs[title].orEmpty()
                for (id in ids) {
                    if (id in now) continue
                    if (id in removed[title].orEmpty()) {
                        gone.getOrPut(title) { HashSet() }.add(id)
                    } else if (title !in deleted) {
                        fail("lost", "$id of $title is no longer on the server")
                    }
                }
            }
            for ((_, attachments) in view.attachments) {
                attachments.values.filterNotNull().firstOrNull { it.size > limits.maxBytes }
                    ?.let { fail("limit", "an attachment item of ${it.size} bytes") }
            }
            for (dev in devices) {
                if (dev.exceptions.isNotEmpty()) fail("exception", "${dev.name}: ${dev.exceptions.first()}")
                checkFiles(dev)
                checkBytes(dev)
            }
        }

        private fun checkFiles(dev: SimDevice) {
            val home = dev.home.canonicalFile
            val attachments = File(home, "attachments")
            for (file in dev.store.written) {
                if (file.canonicalFile.parentFile != attachments) fail("path", "${dev.name} wrote $file")
                if (file.length() > limits.maxBytes) fail("limit", "${dev.name} stored ${file.length()} bytes")
            }
            home.walk().filter { it.isFile }.forEach { file ->
                val top = file.canonicalFile.relativeTo(home).path.substringBefore(File.separatorChar)
                if (top !in setOf("attachments", "files", "vtodo")) fail("path", "${dev.name} wrote $file")
            }
        }

        private fun checkBytes(dev: SimDevice) {
            val downloads = HashMap<String, Int>()
            dev.counters.downloaded.forEach { (uid, count) -> fileOf[uid]?.let { downloads.merge(it, count, Int::plus) } }
            downloads.entries.firstOrNull { it.value > 1 }
                ?.let { fail("bytes", "${dev.name} downloaded ${it.key} ${it.value} times") }
            val uploads = HashMap<String, Int>()
            dev.counters.uploaded.keys.forEach { uid -> fileOf[uid]?.let { uploads.merge(it, 1, Int::plus) } }
            for ((id, count) in uploads) {
                // (a lost reply followed by a removal elsewhere: uploaded once more, see [ambiguous])
                val allowed = 1 + (extra["${dev.name}/${dev.life}/$id"] ?: 0) + (if (id in ambiguous) 1 else 0)
                if (count > allowed) fail("bytes", "${dev.name} uploaded $id $count times (allowed $allowed)")
            }
        }

        private fun quiesce() {
            devices.forEach { it.http.offline = false; it.http.faults.clear(); it.http.hooks.clear() }
            var rounds = 0
            while (true) {
                tick()
                devices.forEach { sync(it, clean = true) }
                rounds++
                val pending = runBlocking { devices.any { pending(it) } }
                if (!pending) break
                if (rounds > 6) fail("no quiescence", "still something to push after $rounds rounds: " +
                    runBlocking { devices.filter { pending(it) }.joinToString { it.name } })
            }
            repeat(2) {
                tick()
                devices.forEach { sync(it, clean = true) }
            }
            checkStep()
        }

        private suspend fun pending(dev: SimDevice): Boolean =
            dev.calendars().any { calendar ->
                dev.dirtyDao.getTasksToPush(calendar.uuid!!).isNotEmpty() || dev.caldavDao.getMoved(calendar.uuid!!).isNotEmpty()
            }

        private fun checkFinal() = runBlocking {
            val view = world.view()
            // title to (list, attachment ids) of each copy on the server: one, or one per device that moved it at the
            // same time as another (each move makes a new item; upstream does the same)
            val server = HashMap<String, MutableList<Pair<String, Set<String>>>>()
            for ((list, tasks) in view.tasks) {
                for ((_, vtodo) in tasks) {
                    server.getOrPut(vtodo.summary ?: "") { ArrayList() }.add(list to view.refs(vtodo).map { it.id }.toSet())
                }
            }
            for ((title, copies) in server) {
                if (copies.size > maxOf(1, movers[title].orEmpty().size)) fail("duplicate", "$title is ${copies.size} times on the server")
            }
            for (dev in devices) {
                val here = HashMap<String, MutableList<Pair<String, Set<String>>>>()
                for ((task, caldavTask) in dev.tasks()) {
                    val title = task.title!!
                    val files = dev.files(task.id)
                    for ((id, data) in files) {
                        if (id in oversize) continue
                        if (data == null) fail("diverged", "$title on ${dev.name}: $id has no file")
                        if (content[id] != null && sha256(data) != sha256(content[id]!!)) fail("diverged", "$title on ${dev.name}: $id differs")
                    }
                    val list = dev.caldavDao.getCalendar(caldavTask.calendar!!)?.url ?: ""
                    here.getOrPut(title) { ArrayList() }.add(list to files.keys - oversize)
                }
                val sorted = { m: Map<String, List<Pair<String, Set<String>>>> -> m.mapValues { (_, v) -> v.map { "${it.first}:${it.second.sorted()}" }.sorted() } }
                if (sorted(here) != sorted(server)) fail("diverged", "${dev.name} has ${sorted(here)}, the server ${sorted(server)}")
            }
            val fields = devices.map { dev -> dev.tasks().map { (t, _) -> "${t.title}|${t.notes}|${t.priority}|${t.isCompleted}" }.sorted() }
            if (fields.distinct().size > 1) fail("diverged", "fields differ between devices: $fields")
            for ((title, ids) in added) {
                val expected = ids - removed[title].orEmpty() - oversize
                val copies = server[title]
                val actual = copies?.flatMap { it.second }?.toSet()
                // copies made by concurrent moves are tasks of their own: a removal from one leaves the other as it is;
                // a task deleted on one device while another moved it is kept as the moving device had it
                val most = if ((copies?.size ?: 0) > 1 || title in deleted) ids - oversize else expected
                when {
                    title !in deleted && actual == null -> fail("lost", "$title is gone, nobody deleted it")
                    title !in deleted && !actual!!.containsAll(expected) -> fail("lost", "$title has $actual, expected $expected")
                    actual != null && !(most + ambiguous).containsAll(actual) -> fail("came back", "$title has $actual, expected at most $most")
                }
            }
        }
    }

    companion object {
        // create, edit, attach, detach, delete, move, duplicate, network, sync, faulty sync, busy sync, restart, reinstall
        private val WEIGHTS = listOf(10, 10, 12, 6, 4, 6, 3, 4, 20, 12, 8, 3, 1)
        private val NESTED = listOf(4, 8, 6, 5, 2, 3, 1, 0, 0, 0, 0, 0, 0)

        val NAMES = listOf(
            "photo.jpg", "../../evil.sh", "/etc/passwd", "a\u0000b.txt", ".hidden", "x".repeat(300), "‮gnp.exe",
            "dir/sub/file.txt", "  ", "", "naïve 文件.pdf", "\\windows\\path.bat", "con", "a\"b;c:d,e.png",
        )

        fun describe(devices: Int, steps: List<Step>) =
            "$devices devices, ${steps.size} steps:\n" + steps.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n")
    }
}
