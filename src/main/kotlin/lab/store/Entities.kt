package lab.store

data class DefinitionRow(
    val name: String,
    val version: Int,
    val status: String,
    val revision: Int,
    val body: String,
    val createdAt: Long,
) {
    val published: Boolean get() = status == "published"
}

data class RunRow(
    val id: String,
    val name: String,
    val version: Int,
    val idempotencyKey: String?,
    val initialState: String,
    val eventsJson: String,
    val responsesJson: String,
    val cursor: Int,
    val completed: Boolean,
    val actionInvocations: Int,
    val createdAt: Long,
)

data class StepRow(
    val runId: String,
    val seq: Int,
    val beforeState: String,
    val eventJson: String,
    val status: String,
    val guardPassed: Boolean,
    val guardDetail: String,
    val outputJson: String?,
    val afterState: String,
    val definitionVersion: Int,
)
