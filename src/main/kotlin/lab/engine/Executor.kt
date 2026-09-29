package lab.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import lab.store.RunRepository
import lab.store.RunRow
import lab.store.StepRow

/**
 * “动作”是实验室里可计数的外部副作用。崩溃恢复测试会在动作触发后让进程退出，
 * 重新启动继续执行，actionInvocations 与 sink 回调都必须只反映真实执行次数。
 */
fun interface ActionSink {
    fun onApplied(runId: String, seq: Int, status: String, output: JsonObject?)
}

object NoopActionSink : ActionSink {
    override fun onApplied(runId: String, seq: Int, status: String, output: JsonObject?) = Unit
}

/** 测试用的确定性崩溃点；触发后由调用方模拟进程退出（异常不会被提交吞掉）。 */
class CrashWindow(
    var crashAfterStepCommittedBeforeCursor: Boolean = false,
    var crashAfterActionBeforeCommit: Boolean = false,
) : RuntimeException("simulated process exit")

class RunExecutor(
    private val runs: RunRepository,
    private val actionSink: ActionSink = NoopActionSink,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun execute(run: RunRow, definition: Definition, crashWindow: CrashWindow? = null): RunRow {
        val events = json.decodeFromString(JsonArray.serializer(), run.eventsJson)
            .map { it as JsonObject }

        // 恢复：已落库的步骤是动作已经发生过的权威记录，直接续跑且绝不重复触发。
        val persisted = runs.steps(run.id)
        var state: JsonObject = persisted.lastOrNull()
            ?.let { json.decodeFromString(JsonObject.serializer(), it.afterState) }
            ?: json.decodeFromString(JsonObject.serializer(), run.initialState)
        var cursor = maxOf(run.cursor, persisted.size)
        var actionInvocations = run.actionInvocations
        val machine = StateMachine(definition)

        for (seq in cursor until events.size) {
            val before = state
            val outcome = machine.apply(before, events[seq])

            // 事务一：步骤落库（执行前状态/输入/守卫/输出/执行后状态/定义版本）+ 动作计数与副作用。
            // 提交前退出 -> 整笔回滚，恢复时从头执行该步，无重复动作。
            val step = StepRow(
                runId = run.id,
                seq = seq,
                beforeState = json.encodeToString(JsonObject.serializer(), before),
                eventJson = json.encodeToString(JsonObject.serializer(), events[seq]),
                status = outcome.status,
                guardPassed = outcome.guard.passed,
                guardDetail = json.encodeToString(JsonElement.serializer(), outcome.guard.detail),
                outputJson = outcome.output?.let { json.encodeToString(JsonObject.serializer(), it) },
                afterState = json.encodeToString(JsonObject.serializer(), outcome.after),
                definitionVersion = run.version,
            )
            runs.db.tx {
                runs.insertStep(step)
                if (outcome.status == StepStatus.APPLIED.name) {
                    runs.incrementActions(run.id)
                    actionInvocations += 1
                    actionSink.onApplied(run.id, seq, outcome.status, outcome.output)
                }
                if (crashWindow?.crashAfterActionBeforeCommit == true) {
                    crashWindow.crashAfterActionBeforeCommit = false
                    throw crashWindow
                }
            }

            state = outcome.after

            // 崩溃窗口：步骤已提交、运行游标尚未推进。恢复时该步骤已在 persisted 中，
            // 游标从 persisted.size 续算，动作不会再次触发。
            if (crashWindow?.crashAfterStepCommittedBeforeCursor == true) {
                crashWindow.crashAfterStepCommittedBeforeCursor = false
                throw crashWindow
            }

            // 事务二：推进游标。两个事务分开提交，构成真实的“写步骤/推进游标”窗口。
            cursor = seq + 1
            runs.advanceCursor(run.id, cursor, actionInvocations, cursor == events.size)
        }

        // 若最后一步提交后在推进游标前退出，恢复时只需把游标补齐。
        if (cursor == events.size) {
            runs.advanceCursor(run.id, cursor, actionInvocations, true)
        }
        return runs.get(run.id)!!
    }
}
