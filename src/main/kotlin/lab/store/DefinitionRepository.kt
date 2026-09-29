package lab.store

import java.sql.ResultSet
import java.sql.SQLException

class VersionConflictException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
class PublishedImmutableException(message: String) : RuntimeException(message)

class DefinitionRepository(private val db: Database) {

    fun createDraft(name: String, body: String, now: Long): DefinitionRow {
        val latest = latestVersionNumber(name)
        require(latest == null) { "definition $name already exists" }
        return insert(name, 1, "draft", 1, body, now)
    }

    /** 基于已发布版本创建新草稿（已发布版本保持不可修改；新版本承载后续修改）。 */
    fun createNextDraft(name: String, body: String, now: Long): DefinitionRow {
        val nextVersion = (latestVersionNumber(name) ?: 0) + 1
        return insert(name, nextVersion, "draft", 1, body, now)
    }

    /**
     * 修改 draft 定义：expectedRevision 提供版本冲突保护，
     * 两个页面同时保存时后提交的一方必须收到冲突。
     */
    fun updateDraft(name: String, version: Int, expectedRevision: Int, body: String, now: Long): DefinitionRow {
        val current = get(name, version) ?: throw NotFoundException("definition not found: $name@$version")
        if (current.status != "draft") {
            throw PublishedImmutableException("definition $name@$version is published and cannot be modified")
        }
        if (current.revision != expectedRevision) {
            throw VersionConflictException(
                "draft $name@$version changed at revision ${current.revision}; expected $expectedRevision",
            )
        }
        val updated = current.copy(revision = expectedRevision + 1, body = body, createdAt = now)
        db.connection.prepareStatement(
            """
            UPDATE definitions
               SET revision = ?, body = ?, created_at = ?
             WHERE name = ? AND version = ? AND status = 'draft'
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, updated.revision)
            ps.setString(2, body)
            ps.setLong(3, now)
            ps.setString(4, name)
            ps.setInt(5, version)
            val affected = ps.executeUpdate()
            if (affected != 1) throw VersionConflictException("draft update lost the race")
        }
        return updated
    }

    fun publish(name: String, version: Int, now: Long): DefinitionRow {
        val current = get(name, version) ?: throw NotFoundException("definition not found: $name@$version")
        if (current.status == "published") return current
        db.connection.prepareStatement(
            "UPDATE definitions SET status = 'published' WHERE name = ? AND version = ?",
        ).use { ps ->
            ps.setString(1, name)
            ps.setInt(2, version)
            ps.executeUpdate()
        }
        return get(name, version)!!
    }

    fun get(name: String, version: Int): DefinitionRow? =
        db.connection.prepareStatement(
            "SELECT * FROM definitions WHERE name = ? AND version = ?",
        ).use { ps ->
            ps.setString(1, name)
            ps.setInt(2, version)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }

    fun latestVersion(name: String): DefinitionRow? =
        listVersions(name).maxByOrNull { it.version }

    fun latestPublished(name: String): DefinitionRow? =
        listVersions(name).filter { it.published }.maxByOrNull { it.version }

    fun listVersions(name: String): List<DefinitionRow> =
        db.connection.prepareStatement(
            "SELECT * FROM definitions WHERE name = ? ORDER BY version",
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> rs.mapRows { toRow() } }
        }

    fun listLatest(): List<DefinitionRow> {
        return db.connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT d.* FROM definitions d
                JOIN (SELECT name, MAX(version) AS v FROM definitions GROUP BY name) m
                  ON m.name = d.name AND m.v = d.version
                ORDER BY d.name
                """.trimIndent(),
            ).use { rs -> rs.mapRows { toRow() } }
        }
    }

    private fun latestVersionNumber(name: String): Int? =
        db.connection.prepareStatement(
            "SELECT MAX(version) AS v FROM definitions WHERE name = ?",
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("v").takeUnless { rs.wasNull() } else null }
        }

    private fun insert(
        name: String,
        version: Int,
        status: String,
        revision: Int,
        body: String,
        now: Long,
    ): DefinitionRow {
        db.connection.prepareStatement(
            """
            INSERT INTO definitions(name, version, status, revision, body, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, name)
            ps.setInt(2, version)
            ps.setString(3, status)
            ps.setInt(4, revision)
            ps.setString(5, body)
            ps.setLong(6, now)
            ps.executeUpdate()
        }
        return DefinitionRow(name, version, status, revision, body, now)
    }

    private fun ResultSet.toRow(): DefinitionRow = DefinitionRow(
        name = getString("name"),
        version = getInt("version"),
        status = getString("status"),
        revision = getInt("revision"),
        body = getString("body"),
        createdAt = getLong("created_at"),
    )
}
