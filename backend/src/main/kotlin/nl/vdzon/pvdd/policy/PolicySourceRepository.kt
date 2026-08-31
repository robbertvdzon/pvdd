package nl.vdzon.pvdd.policy

import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PolicySourceRepository(private val jdbc: JdbcTemplate) : PolicyStore {
    override fun insert(chunk: PolicyChunk): Boolean = jdbc.update(
        """
        INSERT INTO policy_source(
            id, source_url, source_sha256, fetched_at, page_number, chunk_sequence,
            heading, chunk_text, themes
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, string_to_array(?, ','))
        ON CONFLICT (source_sha256, page_number, chunk_sequence) DO NOTHING
        """.trimIndent(),
        chunk.id,
        chunk.sourceUrl.toString(),
        chunk.sourceSha256,
        Timestamp.from(chunk.fetchedAt),
        chunk.pageNumber,
        chunk.sequence,
        chunk.heading,
        chunk.text,
        chunk.themes.joinToString(",") { it.name },
    ) == 1

    override fun countByHash(sourceSha256: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM policy_source WHERE source_sha256 = ?",
        Int::class.java,
        sourceSha256,
    ) ?: 0

    override fun latestSource(): String? = jdbc.queryForList(
        "SELECT source_sha256 FROM policy_source ORDER BY fetched_at DESC, created_at DESC LIMIT 1",
        String::class.java,
    ).firstOrNull()

    override fun findByHash(sourceSha256: String): List<PolicyChunk> = jdbc.query(
        """
        SELECT id, source_url, source_sha256, fetched_at, page_number, chunk_sequence,
               heading, chunk_text, themes
        FROM policy_source
        WHERE source_sha256 = ?
        ORDER BY page_number, chunk_sequence
        """.trimIndent(),
        { rs, _ ->
            val themes = (rs.getArray("themes").array as Array<*>).map { PolicyTheme.valueOf(it.toString()) }.toSet()
            PolicyChunk(
                id = rs.getObject("id", java.util.UUID::class.java),
                sourceUrl = java.net.URI(rs.getString("source_url")),
                sourceSha256 = rs.getString("source_sha256"),
                fetchedAt = rs.getTimestamp("fetched_at").toInstant(),
                pageNumber = rs.getInt("page_number"),
                sequence = rs.getInt("chunk_sequence"),
                heading = rs.getString("heading"),
                text = rs.getString("chunk_text"),
                themes = themes,
            )
        },
        sourceSha256,
    )
}

interface PolicyStore {
    fun insert(chunk: PolicyChunk): Boolean
    fun countByHash(sourceSha256: String): Int
    fun latestSource(): String?
    fun findByHash(sourceSha256: String): List<PolicyChunk>
}
