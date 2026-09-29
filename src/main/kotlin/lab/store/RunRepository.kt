package lab.store

import java.sql.SQLException

class IdempotencyConflictException(message: String) : RuntimeException(message)

class RunRepository(val db: Database) {

    /**
     * 幂等创建：
     * - 相同键 + 相同初始状态/事件 -> 返回原运行（created=false）
     * - 相同键但输入不同 -> 冲突
     */
    fun createOrGet(request: CreateRunRequest): Pair<RunRow, Boolean> {
        val existing = findByIdempotencyKey(request.idempotencyKey)
        if (existing != null) {
            if (existing.initialState != request.initialStateJson ||
                existing.eventsJson != request.eventsJson
            ) {
                throw IdempotencyConflictException(
                    "idempotency key '${request.idempotencyKey}' already exists with different initial state or events",
                )
            }
            return existing to false
        }
        return try {
            insert(request) to true
        } catch (e: SQLException) {
            // 并发下另一个请求先提交了同一个幂等键：重新读取并按相同规则判定。
            val winner = findByIdempotencyKey(request.idempotencyKey)
                ?: throw e
            if (winner.initialState != request.initialStateJson ||
                winner.eventsJson != request.eventsJson
            ) {
                throw IdempotencyConflictException(
                    "idempotency key '${request.idempotencyKey}' already exists with different initial state or events",
                )
            }
            winner to false
        }
    }

    fun get(runId: String): RunRow? =
        db.connection.prepareStatement("SELECT * FROM runs WHERE id = ?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }

    fun steps(runId: String): List<StepRow> =
        db.connection.prepareStatement(
            "SELECT * FROM steps WHERE run_id = ? ORDER BY seq",
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs -> rs.mapRows { toStepRow() } }
        }

    fun insertStep(step: StepRow) {
        db.connection.prepareStatement(
            """
            INSERT INTO steps(
                run_id, seq, before_state, event_json, status,
                guard_passed, guard_detail, output_json, after_state, definition_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, step.runId)
            ps.setInt(2, step.seq)
            ps.setString(3, step.beforeState)
            ps.setString(4, step.eventJson)
            ps.setString(5, step.status)
            ps.setInt(6, if (step.guardPassed) 1 else 0)
            ps.setString(7, step.guardDetail)
            ps.setString(8, step.outputJson)
            ps.setString(9, step.afterState)
            ps.setInt(10, step.definitionVersion)
            ps.executeUpdate()
        }
    }

    fun advanceCursor(runId: String, cursor: Int, actionInvocations: Int, completed: Boolean) {
        db.connection.prepareStatement(
            "UPDATE runs SET cursor = ?, action_invocations = ?, completed = ? WHERE id = ?",
        ).use { ps ->
            ps.setInt(1, cursor)
            ps.setInt(2, actionInvocations)
            ps.setInt(3, if (completed) 1 else 0)
            ps.setString(4, runId)
            ps.executeUpdate()
        }
    }

    fun incrementActions(runId: String) {
        db.connection.prepareStatement(
            "UPDATE runs SET action_invocations = action_invocations + 1 WHERE id = ?",
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeUpdate()
        }
    }

    fun stepExists(runId: String, seq: Int): Boolean =
        db.connection.prepareStatement(
            "SELECT 1 FROM steps WHERE run_id = ? AND seq = ?",
        ).use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, seq)
            ps.executeQuery().use { it.next() }
        }

    fun findByIdempotencyKey(key: String?): RunRow? {
        if (key == null) return null
        return db.connection.prepareStatement("SELECT * FROM runs WHERE idempotency_key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }
    }

    private fun insert(request: CreateRunRequest): RunRow {
        db.connection.prepareStatement(
            """
            INSERT INTO runs(
                id, name, version, idempotency_key, initial_state,
                events_json, responses_json, cursor, completed, action_invocations, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, request.id)
            ps.setString(2, request.name)
            ps.setInt(3, request.version)
            ps.setString(4, request.idempotencyKey)
            ps.setString(5, request.initialStateJson)
            ps.setString(6, request.eventsJson)
            ps.setString(7, request.responsesJson)
            ps.setLong(8, request.createdAt)
            ps.executeUpdate()
        }
        return RunRow(
            id = request.id,
            name = request.name,
            version = request.version,
            idempotencyKey = request.idempotencyKey,
            initialState = request.initialStateJson,
            eventsJson = request.eventsJson,
            responsesJson = request.responsesJson,
            cursor = 0,
            completed = false,
            actionInvocations = 0,
            createdAt = request.createdAt,
        )
    }

    private fun java.sql.ResultSet.toRow(): RunRow = RunRow(
        id = getString("id"),
        name = getString("name"),
        version = getInt("version"),
        idempotencyKey = getString("idempotency_key"),
        initialState = getString("initial_state"),
        eventsJson = getString("events_json"),
        responsesJson = getString("responses_json"),
        cursor = getInt("cursor"),
        completed = getInt("completed") == 1,
        actionInvocations = getInt("action_invocations"),
        createdAt = getLong("created_at"),
    )

    private fun java.sql.ResultSet.toStepRow(): StepRow = StepRow(
        runId = getString("run_id"),
        seq = getInt("seq"),
        beforeState = getString("before_state"),
        eventJson = getString("event_json"),
        status = getString("status"),
        guardPassed = getInt("guard_passed") == 1,
        guardDetail = getString("guard_detail"),
        outputJson = getString("output_json"),
        afterState = getString("after_state"),
        definitionVersion = getInt("definition_version"),
    )
}

data class CreateRunRequest(
    val id: String,
    val name: String,
    val version: Int,
    val idempotencyKey: String?,
    val initialStateJson: String,
    val eventsJson: String,
    val responsesJson: String,
    val createdAt: Long,
)
