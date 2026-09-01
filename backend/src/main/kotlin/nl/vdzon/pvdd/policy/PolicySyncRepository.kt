package nl.vdzon.pvdd.policy

import java.net.URI
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

enum class PolicySyncTrigger { MONTHLY, MANUAL }
enum class PolicySyncStatus { PENDING, RUNNING, QUEUED, WAITING_FOR_WORKER, SUCCEEDED, FAILED, CANCELLED }

data class PolicySyncRunRecord(
    val id: UUID,
    val trigger: PolicySyncTrigger,
    val status: PolicySyncStatus,
    val runtimeJobId: String?,
    val candidateSnapshotId: UUID?,
    val sourceCount: Int,
    val newCount: Int,
    val changedCount: Int,
    val unchangedCount: Int,
    val disappearedCount: Int,
    val errorCode: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val updatedAt: Instant,
)

data class CandidatePolicySource(
    val revisionId: UUID,
    val sourceId: UUID,
    val url: URI,
    val sourceType: PolicyWebSourceType,
    val title: String,
    val publicationDate: LocalDate?,
    val sha256: String,
    val fetchedAt: Instant,
    val text: String,
)

data class CandidatePolicySnapshot(
    val id: UUID?,
    val fingerprint: String,
    val changed: Boolean,
    val sources: List<CandidatePolicySource>,
)

data class PolicyPositionInput(
    val title: String,
    val summary: String,
    val themes: Set<String>,
    val direction: String,
    val status: String,
    val sourceDate: LocalDate?,
    val references: List<PolicyReferenceInput>,
)

data class PolicyReferenceInput(val revisionId: UUID, val pageNumber: Int?, val section: String?)

data class PolicyReferenceDto(
    val url: URI,
    val sourceType: String,
    val title: String,
    val pageNumber: Int?,
    val section: String?,
)

data class PolicyPositionDto(
    val id: UUID,
    val title: String,
    val summary: String,
    val themes: List<String>,
    val direction: String,
    val status: String,
    val sourceDate: LocalDate?,
    val lastChangedAt: Instant,
    val references: List<PolicyReferenceDto>,
)

data class PolicySnapshotDto(
    val id: UUID,
    val version: Int,
    val fingerprint: String,
    val activatedAt: Instant,
)

@Repository
class PolicySyncRepository(private val jdbc: JdbcTemplate) {
    fun createRun(trigger: PolicySyncTrigger, idempotencyKey: String, now: Instant): PolicySyncRunRecord {
        require(idempotencyKey.length in 8..200)
        val id = UUID.randomUUID()
        try {
            jdbc.update(
                """
                INSERT INTO policy_sync_run(id, trigger_type, idempotency_key, status, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                """.trimIndent(),
                id, trigger.name, idempotencyKey, Timestamp.from(now), Timestamp.from(now),
            )
        } catch (_: DataIntegrityViolationException) {
            return findByIdempotencyKey(idempotencyKey) ?: activeRun()
                ?: throw IllegalStateException("POLICY_SYNC_CONFLICT")
        }
        return requireNotNull(run(id))
    }

    fun claimPending(): PolicySyncRunRecord? = jdbc.query(
        """
        WITH candidate AS (
            SELECT id FROM policy_sync_run
            WHERE status = 'PENDING' OR (status = 'RUNNING' AND runtime_job_id IS NULL AND updated_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes')
            ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE policy_sync_run run SET status = 'RUNNING', started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        FROM candidate WHERE run.id = candidate.id
        RETURNING run.*
        """.trimIndent(),
        runMapper,
    ).singleOrNull()

    @Transactional
    fun persistCandidate(runId: UUID, crawl: PolicyCrawlResult): CandidatePolicySnapshot {
        val crawled = crawl.sources
        var newCount = 0
        var changedCount = 0
        var unchangedCount = 0
        val fetchedSources = crawled.map { source ->
            val existing = jdbc.query(
                """
                SELECT source.id, revision.sha256 FROM policy_web_source source
                LEFT JOIN LATERAL (
                    SELECT sha256 FROM policy_web_revision WHERE source_id = source.id ORDER BY fetched_at DESC LIMIT 1
                ) revision ON TRUE
                WHERE source.canonical_url = ?
                """.trimIndent(),
                { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("sha256") },
                source.canonicalUrl.toString(),
            ).singleOrNull()
            when {
                existing == null -> newCount++
                existing.second == source.sha256 -> unchangedCount++
                else -> changedCount++
            }
            val sourceId = existing?.first ?: UUID.randomUUID().also { id ->
                jdbc.update(
                    "INSERT INTO policy_web_source(id, canonical_url, source_type) VALUES (?, ?, ?)",
                    id, source.canonicalUrl.toString(), source.sourceType.name,
                )
            }
            jdbc.update(
                "UPDATE policy_web_source SET source_type = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                source.sourceType.name,
                sourceId,
            )
            val revisionId = jdbc.query(
                """
                INSERT INTO policy_web_revision(
                    id, source_id, sha256, title, publication_date, fetched_at, content_type,
                    size_bytes, etag, last_modified, extracted_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, sha256) DO UPDATE SET
                    fetched_at = EXCLUDED.fetched_at, etag = EXCLUDED.etag, last_modified = EXCLUDED.last_modified
                RETURNING id
                """.trimIndent(),
                { rs, _ -> rs.getObject("id", UUID::class.java) },
                UUID.randomUUID(), sourceId, source.sha256, source.title, source.publicationDate?.let(Date::valueOf),
                Timestamp.from(source.fetchedAt), source.contentType, source.sizeBytes, source.etag,
                source.lastModified, source.extractedText,
            ).single()
            CandidatePolicySource(
                revisionId, sourceId, source.canonicalUrl, source.sourceType, source.title,
                source.publicationDate, source.sha256, source.fetchedAt, source.extractedText,
            )
        }
        val fetchedUrls = fetchedSources.map { it.url }.toSet()
        val currentSources = if (crawl.complete) emptyList() else currentPolicySources()
        if (crawl.unavailableUrls.any { unavailable -> currentSources.none { it.url == unavailable } }) {
            throw PolicySourceException("POLICY_INCOMPLETE_CRAWL")
        }
        val retainedSources = currentSources.filterNot { it.url in fetchedUrls }
        unchangedCount += retainedSources.size
        val candidateSources = (fetchedSources + retainedSources).sortedBy { it.url.toString() }
        if (candidateSources.isEmpty()) throw PolicySourceException("NO_POLICY_SOURCES")
        val fingerprint = sha256(candidateSources.sortedBy { it.url.toString() }.joinToString("\n") { "${it.url}|${it.sha256}" })
        val activeFingerprint = activeSnapshot()?.fingerprint
        val knownUrls = candidateSources.map { it.url.toString() }.toSet()
        val disappeared = if (crawl.complete) jdbc.queryForList(
            "SELECT canonical_url FROM policy_web_source WHERE status = 'CURRENT'",
            String::class.java,
        ).count { it !in knownUrls } else 0
        if (activeFingerprint == fingerprint) {
            updateSourceStatuses(candidateSources)
            jdbc.update(
                """
                UPDATE policy_sync_run SET status = 'SUCCEEDED', source_count = ?, new_count = ?, changed_count = ?,
                    unchanged_count = ?, disappeared_count = ?, completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, error_code = NULL WHERE id = ?
                """.trimIndent(),
                candidateSources.size, newCount, changedCount, unchangedCount, disappeared, runId,
            )
            return CandidatePolicySnapshot(null, fingerprint, false, candidateSources)
        }
        val snapshotId = UUID.randomUUID()
        val nextVersion = (jdbc.queryForObject("SELECT COALESCE(MAX(version_number), 0) + 1 FROM policy_snapshot", Int::class.java) ?: 1)
        jdbc.update(
            "INSERT INTO policy_snapshot(id, version_number, fingerprint, status, created_at) VALUES (?, ?, ?, 'CANDIDATE', CURRENT_TIMESTAMP)",
            snapshotId, nextVersion, fingerprint,
        )
        candidateSources.forEach { source ->
            jdbc.update("INSERT INTO policy_snapshot_source(snapshot_id, revision_id) VALUES (?, ?)", snapshotId, source.revisionId)
        }
        jdbc.update(
            """
            UPDATE policy_sync_run SET candidate_snapshot_id = ?, source_count = ?, new_count = ?, changed_count = ?,
                unchanged_count = ?, disappeared_count = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
            """.trimIndent(),
            snapshotId, candidateSources.size, newCount, changedCount, unchangedCount, disappeared, runId,
        )
        return CandidatePolicySnapshot(snapshotId, fingerprint, true, candidateSources)
    }

    fun markSubmitted(runId: UUID, runtimeJobId: String, status: PolicySyncStatus) {
        jdbc.update(
            "UPDATE policy_sync_run SET runtime_job_id = ?, status = ?, updated_at = CURRENT_TIMESTAMP, error_code = NULL WHERE id = ?",
            runtimeJobId, status.name, runId,
        )
    }

    fun activeRuntimeRuns(): List<PolicySyncRunRecord> = jdbc.query(
        "SELECT * FROM policy_sync_run WHERE runtime_job_id IS NOT NULL AND status IN ('QUEUED', 'WAITING_FOR_WORKER', 'RUNNING') ORDER BY created_at",
        runMapper,
    )

    fun updateRuntimeStatus(runId: UUID, status: PolicySyncStatus) {
        jdbc.update("UPDATE policy_sync_run SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status.name, runId)
    }

    @Transactional
    fun activate(run: PolicySyncRunRecord, positions: List<PolicyPositionInput>, completedAt: Instant) {
        val snapshotId = requireNotNull(run.candidateSnapshotId)
        val allowedRevisionIds = candidateSources(snapshotId).map { it.revisionId }.toSet()
        require(positions.isNotEmpty() && positions.size <= 100)
        positions.forEach { position ->
            require(position.references.isNotEmpty() && position.references.all { it.revisionId in allowedRevisionIds })
            val positionId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO policy_position(id, snapshot_id, title, summary, themes, direction, status, source_date)
                VALUES (?, ?, ?, ?, string_to_array(?, ','), ?, ?, ?)
                """.trimIndent(),
                positionId, snapshotId, position.title, position.summary, position.themes.joinToString(","),
                position.direction, position.status, position.sourceDate?.let(Date::valueOf),
            )
            position.references.distinct().forEach { reference ->
                jdbc.update(
                    """
                    INSERT INTO policy_position_reference(position_id, revision_id, page_number, section)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """.trimIndent(),
                    positionId, reference.revisionId, reference.pageNumber ?: 0, reference.section.orEmpty(),
                )
            }
        }
        jdbc.update("UPDATE policy_snapshot SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'")
        jdbc.update(
            "UPDATE policy_snapshot SET status = 'ACTIVE', activated_at = ? WHERE id = ? AND status = 'CANDIDATE'",
            Timestamp.from(completedAt), snapshotId,
        )
        updateSourceStatuses(candidateSources(snapshotId))
        refreshAnalysisPolicySource(snapshotId, completedAt)
        jdbc.update(
            "UPDATE policy_sync_run SET status = 'SUCCEEDED', completed_at = ?, updated_at = ?, error_code = NULL WHERE id = ?",
            Timestamp.from(completedAt), Timestamp.from(completedAt), run.id,
        )
    }

    fun fail(runId: UUID, errorCode: String) {
        jdbc.update(
            """
            UPDATE policy_snapshot SET status = 'FAILED'
            WHERE id = (SELECT candidate_snapshot_id FROM policy_sync_run WHERE id = ?) AND status = 'CANDIDATE'
            """.trimIndent(),
            runId,
        )
        jdbc.update(
            "UPDATE policy_sync_run SET status = 'FAILED', error_code = ?, completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            errorCode.take(120), runId,
        )
    }

    fun run(id: UUID): PolicySyncRunRecord? = jdbc.query("SELECT * FROM policy_sync_run WHERE id = ?", runMapper, id).singleOrNull()
    fun activeRun(): PolicySyncRunRecord? = jdbc.query(
        "SELECT * FROM policy_sync_run WHERE status IN ('PENDING','RUNNING','QUEUED','WAITING_FOR_WORKER') ORDER BY created_at LIMIT 1",
        runMapper,
    ).singleOrNull()
    fun latestRun(): PolicySyncRunRecord? = jdbc.query("SELECT * FROM policy_sync_run ORDER BY created_at DESC LIMIT 1", runMapper).singleOrNull()
    fun latestSuccessfulRun(): PolicySyncRunRecord? = jdbc.query(
        "SELECT * FROM policy_sync_run WHERE status = 'SUCCEEDED' ORDER BY completed_at DESC LIMIT 1",
        runMapper,
    ).singleOrNull()

    fun activeSnapshot(): PolicySnapshotDto? = jdbc.query(
        "SELECT id, version_number, fingerprint, activated_at FROM policy_snapshot WHERE status = 'ACTIVE'",
        { rs, _ -> PolicySnapshotDto(
            rs.getObject("id", UUID::class.java), rs.getInt("version_number"), rs.getString("fingerprint"),
            rs.getTimestamp("activated_at").toInstant(),
        ) },
    ).singleOrNull()

    fun positions(snapshotId: UUID): List<PolicyPositionDto> = jdbc.query(
        """
        SELECT id, title, summary, themes, direction, status, source_date, created_at
        FROM policy_position WHERE snapshot_id = ? ORDER BY title
        """.trimIndent(),
        { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            PolicyPositionDto(
                id, rs.getString("title"), rs.getString("summary"),
                (rs.getArray("themes").array as Array<*>).map(Any?::toString),
                rs.getString("direction"), rs.getString("status"), rs.getDate("source_date")?.toLocalDate(),
                rs.getTimestamp("created_at").toInstant(), references(id),
            )
        },
        snapshotId,
    )

    fun position(id: UUID): PolicyPositionDto? = activeSnapshot()?.let { snapshot -> positions(snapshot.id).singleOrNull { it.id == id } }

    fun candidateSources(snapshotId: UUID): List<CandidatePolicySource> = jdbc.query(
        """
        SELECT revision.id revision_id, source.id source_id, source.canonical_url, source.source_type,
               revision.title, revision.publication_date, revision.sha256, revision.fetched_at, revision.extracted_text
        FROM policy_snapshot_source link
        JOIN policy_web_revision revision ON revision.id = link.revision_id
        JOIN policy_web_source source ON source.id = revision.source_id
        WHERE link.snapshot_id = ? ORDER BY source.canonical_url
        """.trimIndent(),
        { rs, _ -> CandidatePolicySource(
            rs.getObject("revision_id", UUID::class.java), rs.getObject("source_id", UUID::class.java),
            URI(rs.getString("canonical_url")), PolicyWebSourceType.valueOf(rs.getString("source_type")),
            rs.getString("title"), rs.getDate("publication_date")?.toLocalDate(), rs.getString("sha256"),
            rs.getTimestamp("fetched_at").toInstant(), rs.getString("extracted_text"),
        ) },
        snapshotId,
    )

    private fun currentPolicySources(): List<CandidatePolicySource> = jdbc.query(
        """
        SELECT revision.id revision_id, source.id source_id, source.canonical_url, source.source_type,
               revision.title, revision.publication_date, revision.sha256, revision.fetched_at, revision.extracted_text
        FROM policy_web_source source
        JOIN LATERAL (
            SELECT * FROM policy_web_revision revision
            WHERE revision.source_id = source.id ORDER BY revision.fetched_at DESC LIMIT 1
        ) revision ON TRUE
        WHERE source.status = 'CURRENT' ORDER BY source.canonical_url
        """.trimIndent(),
        { rs, _ -> CandidatePolicySource(
            rs.getObject("revision_id", UUID::class.java), rs.getObject("source_id", UUID::class.java),
            URI(rs.getString("canonical_url")), PolicyWebSourceType.valueOf(rs.getString("source_type")),
            rs.getString("title"), rs.getDate("publication_date")?.toLocalDate(), rs.getString("sha256"),
            rs.getTimestamp("fetched_at").toInstant(), rs.getString("extracted_text"),
        ) },
    )

    private fun findByIdempotencyKey(key: String): PolicySyncRunRecord? = jdbc.query(
        "SELECT * FROM policy_sync_run WHERE idempotency_key = ?", runMapper, key,
    ).singleOrNull()

    private fun references(positionId: UUID): List<PolicyReferenceDto> = jdbc.query(
        """
        SELECT source.canonical_url, source.source_type, revision.title, reference.page_number, reference.section
        FROM policy_position_reference reference
        JOIN policy_web_revision revision ON revision.id = reference.revision_id
        JOIN policy_web_source source ON source.id = revision.source_id
        WHERE reference.position_id = ? ORDER BY source.canonical_url, reference.page_number, reference.section
        """.trimIndent(),
        { rs, _ -> PolicyReferenceDto(
            URI(rs.getString("canonical_url")), rs.getString("source_type"), rs.getString("title"),
            rs.getInt("page_number").takeIf { it > 0 }, rs.getString("section").takeIf(String::isNotBlank),
        ) },
        positionId,
    )

    private fun updateSourceStatuses(candidateSources: List<CandidatePolicySource>) {
        val ids = candidateSources.map(CandidatePolicySource::sourceId)
        jdbc.update("UPDATE policy_web_source SET status = 'DISAPPEARED', updated_at = CURRENT_TIMESTAMP WHERE status = 'CURRENT'")
        ids.forEach { id -> jdbc.update("UPDATE policy_web_source SET status = 'CURRENT', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id) }
    }

    private fun refreshAnalysisPolicySource(snapshotId: UUID, fetchedAt: Instant) {
        val fingerprint = jdbc.queryForObject("SELECT fingerprint FROM policy_snapshot WHERE id = ?", String::class.java, snapshotId)!!
        var sequence = 0
        candidateSources(snapshotId).forEach { source ->
            source.text.chunked(2_500).filter(String::isNotBlank).forEach { chunk ->
                sequence++
                jdbc.update(
                    """
                    INSERT INTO policy_source(
                        id, source_url, source_sha256, fetched_at, page_number, chunk_sequence,
                        heading, chunk_text, themes
                    ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, string_to_array(?, ','))
                    ON CONFLICT (source_sha256, page_number, chunk_sequence) DO NOTHING
                    """.trimIndent(),
                    UUID.randomUUID(), source.url.toString(), fingerprint, Timestamp.from(fetchedAt), sequence,
                    source.title, chunk, PolicyThemeClassifier.classify(chunk).joinToString(",") { it.name },
                )
            }
        }
    }

    private val runMapper = { rs: java.sql.ResultSet, _: Int ->
        PolicySyncRunRecord(
            rs.getObject("id", UUID::class.java), PolicySyncTrigger.valueOf(rs.getString("trigger_type")),
            PolicySyncStatus.valueOf(rs.getString("status")), rs.getString("runtime_job_id"),
            rs.getObject("candidate_snapshot_id", UUID::class.java), rs.getInt("source_count"), rs.getInt("new_count"),
            rs.getInt("changed_count"), rs.getInt("unchanged_count"), rs.getInt("disappeared_count"),
            rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("completed_at")?.toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class PolicySnapshotActivatedEvent(val snapshotId: UUID)
