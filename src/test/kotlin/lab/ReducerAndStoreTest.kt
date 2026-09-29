package lab

import kotlinx.serialization.json.JsonObject
import lab.engine.DefinitionParser
import lab.engine.DivergenceType
import lab.engine.Reducer
import lab.engine.ResponseComparator
import lab.engine.RunExecutor
import lab.engine.seed.SeedData
import lab.store.CreateRunRequest
import lab.store.DefinitionRepository
import lab.store.IdempotencyConflictException
import lab.store.PublishedImmutableException
import lab.store.RunRepository
import lab.store.VersionConflictException
import lab.store.openDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReducerAndStoreTest {

    @Test
    fun `six step failing trace reduces deterministically to a two step counterexample`() {
        val def = DefinitionParser.parse(SeedData.definitionBody)
        val events = SeedData.traceEvents
        val responses = SeedData.externalResponses

        val result = Reducer.reduce(def, def.initial, events, responses)
        assertEquals(6, result.originalSize)
        assertEquals(listOf(4, 5), result.reducedIndices)
        assertEquals(2, result.reducedIndices.size)
        assertEquals(listOf("cancel", "confirm"), result.reducedSimulation.steps.map {
            (it.event["name"] as kotlinx.serialization.json.JsonPrimitive).content
        })
        assertEquals(DivergenceType.MISMATCH, result.divergence.type)
        assertTrue(result.attempts.isNotEmpty())
        assertTrue(result.attempts.all {
            (it.verdict == "removed") == it.divergent
        })
    }

    @Test
    fun `reduction is stable across runs and idempotent on the reduced trace`() {
        val def = DefinitionParser.parse(SeedData.definitionBody)
        val runs = (0 until 5).map {
            Reducer.reduce(def, def.initial, SeedData.traceEvents, SeedData.externalResponses)
        }
        runs.forEach {
            assertEquals(listOf(4, 5), it.reducedIndices)
            assertEquals(it.attempts, runs.first().attempts)
        }
        // 对缩减结果再缩减：已经最小，索引保持 [0,1]（相对缩减后的轨迹），无法继续删除。
        val first = runs.first()
        val again = Reducer.reduce(
            def,
            def.initial,
            first.reducedSimulation.steps.map { it.event },
            first.reducedResponses,
        )
        assertEquals(listOf(0, 1), again.reducedIndices)
        assertTrue(again.attempts.none { it.verdict == "removed" })
    }

    @TempDir
    lateinit var tempDir: Path

    private fun newDb(file: String = "lab-test.db"): Triple<DefinitionRepository, RunRepository, RunExecutor> {
        val db = openDatabase(tempDir.resolve(UUID.randomUUID().toString()).resolve(file))
        val defs = DefinitionRepository(db)
        val runs = RunRepository(db)
        return Triple(defs, runs, RunExecutor(runs))
    }

    private fun runRequest(key: String?, initial: String, events: String, responses: String = "[]") =
        CreateRunRequest(
            id = UUID.randomUUID().toString(),
            name = SeedData.NAME,
            version = 1,
            idempotencyKey = key,
            initialStateJson = initial,
            eventsJson = events,
            responsesJson = responses,
            createdAt = System.currentTimeMillis(),
        )

    @Test
    fun `same idempotency key returns the original run but conflicts on different input`() {
        val (defs, runs, executor) = newDb()
        val def = DefinitionParser.parse(SeedData.definitionBody)
        val body = TestFixtures.json.encodeToString(JsonObject.serializer(), SeedData.definitionBody)
        defs.createDraft(SeedData.NAME, body, 1L)
        defs.publish(SeedData.NAME, 1, 2L)

        val initial = TestFixtures.json.encodeToString(JsonObject.serializer(), def.initial)
        val events = TestFixtures.json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(SeedData.traceEvents),
        )
        val responses = TestFixtures.json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(SeedData.externalResponses),
        )

        val (first, created1) = runs.createOrGet(runRequest("k-1", initial, events, responses))
        assertTrue(created1)
        executor.execute(first, def)
        val (second, created2) = runs.createOrGet(runRequest("k-1", initial, events, responses))
        assertEquals(first.id, second.id)
        assertEquals(false, created2)

        val otherEvents = TestFixtures.json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(SeedData.traceEvents.take(3)),
        )
        assertFailsWith<IdempotencyConflictException> {
            runs.createOrGet(runRequest("k-1", initial, otherEvents, "[]"))
        }
    }

    @Test
    fun `published definitions are immutable and drafts use revision conflict protection`() {
        val (defs, _, _) = newDb()
        val body = TestFixtures.json.encodeToString(JsonObject.serializer(), SeedData.definitionBody)
        val draft = defs.createDraft("d1", body, 1L)
        val updated = defs.updateDraft("d1", 1, draft.revision, body, 2L)
        assertEquals(2, updated.revision)

        // 两个页面基于 revision 1：先到的成功，后到的收到版本冲突。
        assertFailsWith<VersionConflictException> {
            defs.updateDraft("d1", 1, 1, body, 3L)
        }
        defs.publish("d1", 1, 4L)
        assertFailsWith<PublishedImmutableException> {
            defs.updateDraft("d1", 1, 2, body, 5L)
        }
        // 发布后修改只能开新版本。
        val v2 = defs.createNextDraft("d1", body, 6L)
        assertEquals(2, v2.version)
    }

    @Test
    fun `crash between writing a step and advancing the cursor does not repeat actions`() {
        val (defs, runs, executor) = newDb()
        val def = DefinitionParser.parse(SeedData.definitionBody)
        val body = TestFixtures.json.encodeToString(JsonObject.serializer(), SeedData.definitionBody)
        defs.createDraft(SeedData.NAME, body, 1L)
        defs.publish(SeedData.NAME, 1, 2L)

        val initial = TestFixtures.json.encodeToString(JsonObject.serializer(), def.initial)
        val events = TestFixtures.json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(SeedData.traceEvents),
        )
        val (run, _) = runs.createOrGet(runRequest("crash-1", initial, events))

        val fired = mutableListOf<Int>()
        val countingExecutor = RunExecutor(runs) { _, seq, _, _ -> fired += seq }

        // 进程在第 0 步落库后、游标推进前退出。
        assertFailsWith<RuntimeException> {
            countingExecutor.execute(
                runs.get(run.id)!!,
                def,
                lab.engine.CrashWindow(crashAfterStepCommittedBeforeCursor = true),
            )
        }
        assertEquals(listOf(0), fired.toList())
        val stuck = runs.get(run.id)!!
        assertEquals(0, stuck.cursor)
        assertEquals(1, runs.steps(run.id).size)
        assertEquals(1, stuck.actionInvocations) // 第 0 步（retry）动作已落账

        // 新“进程”恢复：第 0 步不重复动作，从第 1 步继续。
        countingExecutor.execute(runs.get(run.id)!!, def)
        val recovered = runs.get(run.id)!!
        assertEquals(6, recovered.cursor)
        assertTrue(recovered.completed)
        assertEquals(listOf(0, 1, 2, 3, 4), fired.toList())
        assertEquals(5, recovered.actionInvocations)
        assertEquals(6, runs.steps(run.id).size)
    }

    @Test
    fun `crash before step commit replays the step exactly once`() {
        val (defs, runs, _) = newDb()
        val def = DefinitionParser.parse(SeedData.definitionBody)
        val body = TestFixtures.json.encodeToString(JsonObject.serializer(), SeedData.definitionBody)
        defs.createDraft(SeedData.NAME, body, 1L)
        defs.publish(SeedData.NAME, 1, 2L)

        val initial = TestFixtures.json.encodeToString(JsonObject.serializer(), def.initial)
        val events = TestFixtures.json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(SeedData.traceEvents),
        )
        val (run, _) = runs.createOrGet(runRequest("crash-2", initial, events))

        val fired = mutableListOf<Int>()
        val ex = RunExecutor(runs) { _, seq, _, _ -> fired += seq }
        assertFailsWith<RuntimeException> {
            ex.execute(
                runs.get(run.id)!!,
                def,
                lab.engine.CrashWindow(crashAfterActionBeforeCommit = true),
            )
        }
        // 事务回滚：步骤不可见，但动作已经不可收回地触发了一次。
        assertEquals(0, runs.steps(run.id).size)
        ex.execute(runs.get(run.id)!!, def)
        val recovered = runs.get(run.id)!!
        assertEquals(5, recovered.actionInvocations)
        assertEquals(6, runs.steps(run.id).size)
        assertEquals(listOf(0, 0, 1, 2, 3, 4), fired.toList())
    }
}
