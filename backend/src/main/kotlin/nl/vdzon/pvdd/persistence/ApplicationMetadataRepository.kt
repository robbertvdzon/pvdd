package nl.vdzon.pvdd.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class ApplicationMetadataRepository(private val jdbcTemplate: JdbcTemplate) {
    fun get(key: String): String? = jdbcTemplate.queryForList(
        "SELECT metadata_value FROM application_metadata WHERE metadata_key = ?",
        String::class.java,
        key,
    ).firstOrNull()

    fun put(key: String, value: String) {
        jdbcTemplate.update(
            """
            INSERT INTO application_metadata(metadata_key, metadata_value, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (metadata_key) DO UPDATE
            SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(), key, value,
        )
    }
}
