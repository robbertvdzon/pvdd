package nl.vdzon.pvdd.policy

import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PolicySourceRepository(private val jdbc: JdbcTemplate) {
    fun insert(chunk: PolicyChunk): Boolean = jdbc.update(
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

    fun countByHash(sourceSha256: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM policy_source WHERE source_sha256 = ?",
        Int::class.java,
        sourceSha256,
    ) ?: 0
}
