package lab.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

class Database(val connection: Connection) {

    fun migrate() {
        connection.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS definitions (
                    name TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (name, version),
                    UNIQUE (name, version, revision)
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    idempotency_key TEXT,
                    initial_state TEXT NOT NULL,
                    events_json TEXT NOT NULL,
                    responses_json TEXT NOT NULL,
                    cursor INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    action_invocations INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS steps (
                    run_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    before_state TEXT NOT NULL,
                    event_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    guard_passed INTEGER NOT NULL,
                    guard_detail TEXT NOT NULL,
                    output_json TEXT,
                    after_state TEXT NOT NULL,
                    definition_version INTEGER NOT NULL,
                    PRIMARY KEY (run_id, seq),
                    FOREIGN KEY (run_id) REFERENCES runs(id)
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_runs_idempotency
                ON runs(idempotency_key)
                WHERE idempotency_key IS NOT NULL
                """.trimIndent(),
            )
        }
    }

    fun <T> tx(immediate: Boolean = false, block: () -> T): T {
        val oldAutoCommit = connection.autoCommit
        if (immediate) {
            // autoCommit=false 已经意味着开启了事务；仅在保持自动提交时显式 BEGIN IMMEDIATE。
            if (oldAutoCommit) {
                connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            }
        } else {
            connection.autoCommit = false
        }
        try {
            val result = block()
            connection.commit()
            return result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            if (immediate && oldAutoCommit) {
                connection.autoCommit = true
            } else {
                connection.autoCommit = oldAutoCommit
            }
        }
    }
}

fun openDatabase(path: Path): Database {
    val parent = path.toAbsolutePath().parent
    if (parent != null) Files.createDirectories(parent)
    val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")
    val db = Database(conn)
    db.migrate()
    return db
}

fun <T> ResultSet.mapRows(mapper: ResultSet.() -> T): List<T> {
    val out = mutableListOf<T>()
    while (next()) out += mapper()
    return out
}
