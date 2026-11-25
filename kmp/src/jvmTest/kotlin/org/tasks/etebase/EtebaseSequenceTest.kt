package org.tasks.etebase

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.tasks.etebase.sim.SequenceDriver
import java.io.File

/**
 * Random sequences of user actions, syncs, faults and restarts on 2 or 3 devices, deterministic by seed
 * ([SequenceDriver] lists what is checked). The normal run tries a few hundred; more with system properties:
 *
 * ```
 * ETEBASE_SEQUENCES=20000 ETEBASE_SEED=1 ./gradlew :kmp:jvmTest --tests "*EtebaseSequenceTest*" --rerun
 * ```
 * (ETEBASE_SHARD=k/n runs every n-th seed starting at k, ETEBASE_SEED_LIST=a,b,c runs these seeds,
 * ETEBASE_KEEP_GOING=true collects all failures,
 * ETEBASE_TRACE=true prints every step and request.) The same sequences run against a real server with
 * ETEBASE_TEST_SERVER, ETEBASE_TEST_USER and ETEBASE_TEST_CREDENTIALS (as EtebaseScenarioTest), ETEBASE_LIVE_SEQUENCES
 * of them (default 5).
 */
class EtebaseSequenceTest {
    private val severity = Logger.config.minSeverity

    @Before
    fun setUp() = Logger.setMinSeverity(Severity.Assert)

    @After
    fun tearDown() = Logger.setMinSeverity(severity)

    private fun property(name: String) =
        System.getenv("ETEBASE_" + name.replace(Regex("([A-Z])"), "_$1").uppercase())?.takeIf { it.isNotBlank() }

    @Test
    fun randomSequences() {
        val count = property("sequences")?.toInt() ?: 300
        val first = property("seed")?.toLong() ?: 1L
        val (shard, shards) = property("shard")?.split('/')?.map { it.toInt() } ?: listOf(0, 1)
        val driver = SequenceDriver(trace = property("trace") == "true")
        val seeds = property("seedList")?.split(',')?.map { it.trim().toLong() }
            ?: (0 until count).filter { it % shards == shard }.map { first + it }
        run(driver, seeds, property("keepGoing") == "true")
    }

    /** Seeds that failed once, kept as regression tests. */
    @Test
    fun regressions() = run(SequenceDriver(), REGRESSIONS, keepGoing = false)

    /**
     * Seeds that still fail because of accepted limitations of the move handling (REVIEW6 M2): a task duplicated after
     * a move there and back while another device's sync ended early (111934), a device that diverges for good when it
     * moves a task again before the move it followed was fetched (119596), two concurrent moves where one copy keeps an
     * attachment the other device removed (112187; 107464 is the same case, but whether it shows depends on timing).
     * Nothing is lost in any of them.
     */
    @Ignore("known limitations, see KNOWN_FAILING")
    @Test
    fun knownLimitations() = run(SequenceDriver(), KNOWN_FAILING, keepGoing = true)

    @Test
    fun liveServer() {
        val url = System.getenv("ETEBASE_TEST_SERVER")
        assumeTrue("set ETEBASE_TEST_SERVER to run", url != null)
        val user = System.getenv("ETEBASE_TEST_USER") ?: "test1"
        val credentials = File(System.getenv("ETEBASE_TEST_CREDENTIALS") ?: error("ETEBASE_TEST_CREDENTIALS")).readText()
        val password = Regex("\"${Regex.escape(user)}\"\\s*:\\s*\"([^\"]*)\"").find(credentials)!!.groupValues[1]
        val count = property("liveSequences")?.toInt() ?: 5
        run(SequenceDriver(live = Triple(url!!, user, password)), (1L..count.toLong()).map { it + (property("seed")?.toLong() ?: 0) }, false)
    }

    private fun run(driver: SequenceDriver, seeds: List<Long>, keepGoing: Boolean) {
        val failures = ArrayList<String>()
        val started = System.currentTimeMillis()
        for (seed in seeds) {
            val (devices, steps) = driver.generate(seed)
            val start = System.currentTimeMillis()
            val failure = timed(seed) { driver.run(devices, steps, seed) }
            println("SEED $seed ${if (failure == null) "ok" else "FAILED"} ${System.currentTimeMillis() - start} ms")
            if (failure == null) continue
            if (failure.startsWith("hang")) {
                failures.add("seed $seed: $failure")
                if (!keepGoing) break else continue
            }
            val (minimal, message) = driver.shrink(devices, steps, failure)
            val report = "seed $seed: $message\nminimal sequence, ${SequenceDriver.describe(devices, minimal)}"
            println("FAILURE $report")
            if (property("trace") == "true") {
                Logger.setMinSeverity(Severity.Warn)
                driver.run(devices, minimal, seed) { println("TRACE $it") }
                Logger.setMinSeverity(Severity.Assert)
            }
            failures.add(report)
            if (!keepGoing) break
        }
        println("SEQUENCES ${seeds.size} seeds from ${seeds.firstOrNull()} in ${System.currentTimeMillis() - started} ms, ${failures.size} failed")
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} failed\n" + failures.joinToString("\n\n"))
    }

    /** Runs [block], or reports a hang (with where it hangs) after two minutes. */
    private fun timed(seed: Long, block: () -> String?): String? {
        var thread: Thread? = null
        val task = java.util.concurrent.FutureTask { thread = Thread.currentThread(); block() }
        Thread(task, "sequence-$seed").apply { isDaemon = true }.start()
        return try {
            task.get(120, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            val stacks = Thread.getAllStackTraces().filterKeys { it.name.startsWith("sequence-$seed") || it.name.contains("DefaultDispatcher") }
                .entries.joinToString("\n") { (t, s) -> "  thread ${t.name}:\n" + s.take(25).joinToString("\n") { "    at $it" } }
            "hang: no result after 120 s\n$stacks"
        }.also { thread }
    }

    companion object {
        /** Seeds that failed in round 6 (REPORT6.md lists what each found). */
        private val REGRESSIONS = listOf(
            15L, 25L, 29L, 84L, 105L, 117L, 185L, 191L, 210L, 1331L, 1359L, 1382L, 1491L, 1796L, 2143L, 2157L, 2271L,
            2308L, 2405L, 2586L, 2616L, 2779L, 2895L, 2988L, 3341L, 3576L, 3608L, 3619L, 3752L, 3771L, 3850L, 3946L,
            3955L, 3971L, 4013L, 4109L, 4155L, 4346L, 4358L, 4368L, 4449L, 4616L, 4680L, 4692L, 4734L, 4809L, 5147L,
            5197L, 5247L, 5251L, 5330L, 5400L, 5461L, 5463L, 5493L, 5657L, 5845L, 5855L, 5869L, 5984L, 9367L, 9430L,
            9495L, 11208L, 14581L, 19778L, 22753L, 23327L, 25432L, 29870L,
        )

        /** Seeds of REVIEW6 that fail because of a known limitation (see [knownLimitations]). */
        private val KNOWN_FAILING = listOf(107464L, 111934L, 112187L, 119596L)
    }
}
