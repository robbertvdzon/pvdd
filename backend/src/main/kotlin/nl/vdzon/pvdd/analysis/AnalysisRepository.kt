package nl.vdzon.pvdd.analysis

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.type.TypeReference

data class PreparedAnalysisRun(
    val run: AnalysisRun,
    val meetingId: UUID,
    val category: String,
    val agendaItemSourceId: String,
    val prompt: String?,
    val responseSchema: JsonNode,
    val allowedSources: List<AnalysisSource>,
    val runType: AnalysisRunType = AnalysisRunType.FINAL_ADVICE,
    val phaseIndex: Int = 0,
    val parentRunId: UUID? = null,
)

data class RunControl(val id: UUID, val meetingId: UUID, val runtimeJobId: String?, val status: AnalysisStatus)

@Repository
class AnalysisRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun queueMeeting(meetingId: UUID) {
        jdbc.update(
            """
            INSERT INTO analysis_meeting_queue(meeting_id, status)
            VALUES (?, 'PENDING')
            ON CONFLICT (meeting_id) DO UPDATE SET
                status = 'PENDING', error_code = NULL,
                updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
            meetingId,
        )
    }

    fun claimMeeting(): UUID? = jdbc.query(
        """
        WITH candidate AS (
            SELECT meeting_id FROM analysis_meeting_queue
            WHERE status = 'PENDING' OR (status = 'CLAIMED' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE analysis_meeting_queue q
        SET status = 'CLAIMED', attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP
        FROM candidate c WHERE q.meeting_id = c.meeting_id
        RETURNING q.meeting_id
        """.trimIndent(),
        { rs, _ -> rs.getObject("meeting_id", UUID::class.java) },
    ).singleOrNull()

    fun finishMeetingPreparation(meetingId: UUID) {
        jdbc.update("UPDATE analysis_meeting_queue SET status = 'COMPLETE', error_code = NULL, updated_at = CURRENT_TIMESTAMP WHERE meeting_id = ?", meetingId)
    }

    fun retryMeetingPreparation(meetingId: UUID, errorCode: String) {
        jdbc.update(
            """
            UPDATE analysis_meeting_queue SET
                status = CASE WHEN attempt_count >= 5 THEN 'FAILED' ELSE 'PENDING' END,
                error_code = ?, updated_at = CURRENT_TIMESTAMP
            WHERE meeting_id = ?
            """.trimIndent(),
            errorCode,
            meetingId,
        )
    }

    fun createRun(meetingId: UUID, run: AnalysisRun): UUID = jdbc.query(
        """
        INSERT INTO analysis_run(
            id, meeting_id, agenda_item_id, source_fingerprint, prompt_version,
            selection_version, idempotency_key, runtime_job_id, status, error_code,
            created_at, updated_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (idempotency_key) DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
        RETURNING id
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        run.id,
        meetingId,
        run.agendaItemId,
        run.sourceFingerprint,
        run.promptVersion,
        run.selectionVersion,
        run.idempotencyKey,
        run.runtimeJobId,
        run.status.name,
        run.errorCode,
        Timestamp.from(run.createdAt),
        Timestamp.from(run.updatedAt),
        run.completedAt?.let(Timestamp::from),
    ).single()

    fun createPreparedRun(prepared: PreparedAnalysisRun): UUID {
        val runId = createRun(prepared.meetingId, prepared.run)
        jdbc.update(
            """
            UPDATE analysis_run SET category = ?, agenda_item_source_id = ?, prompt_text = ?,
                response_schema = CAST(? AS jsonb), allowed_sources = CAST(? AS jsonb),
                run_type = ?, phase_index = ?, parent_run_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND prompt_text IS NULL
            """.trimIndent(),
            prepared.category,
            prepared.agendaItemSourceId,
            prepared.prompt,
            mapper.writeValueAsString(prepared.responseSchema),
            mapper.writeValueAsString(prepared.allowedSources),
            prepared.runType.name,
            prepared.phaseIndex,
            prepared.parentRunId,
            runId,
        )
        reactivateSucceededAdvice(runId, prepared.run.agendaItemId)
        return runId
    }

    private fun reactivateSucceededAdvice(runId: UUID, agendaItemId: UUID) {
        jdbc.update(
            """
            UPDATE agenda_item_advice advice SET actuality = CASE
                WHEN advice.analysis_run_id = ? AND EXISTS (
                    SELECT 1 FROM analysis_run run
                    WHERE run.id = advice.analysis_run_id AND run.status = 'SUCCEEDED'
                ) THEN 'CURRENT'
                ELSE 'STALE'
            END
            WHERE advice.agenda_item_id = ?
              AND (SELECT source_state FROM agenda_item WHERE id = ?) <> 'WITHDRAWN'
            """.trimIndent(),
            runId,
            agendaItemId,
            agendaItemId,
        )
    }

    @org.springframework.transaction.annotation.Transactional
    fun createPhasedRuns(finalRun: PreparedAnalysisRun, noteRuns: List<PreparedAnalysisRun>) {
        require(finalRun.runType == AnalysisRunType.FINAL_ADVICE && finalRun.prompt == null)
        require(noteRuns.isNotEmpty() && noteRuns.all { it.runType == AnalysisRunType.SOURCE_NOTES && it.parentRunId == finalRun.run.id })
        val persistedFinalId = createPreparedRun(finalRun)
        noteRuns.forEach { createPreparedRun(it.copy(parentRunId = persistedFinalId)) }
    }

    fun claimPendingRun(): PreparedAnalysisRun? = jdbc.query(
        """
        WITH candidate AS (
            SELECT id FROM analysis_run
            WHERE (outbox_status = 'PENDING' OR (outbox_status = 'CLAIMED' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'))
              AND status = 'PENDING' AND prompt_text IS NOT NULL
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE analysis_run r SET outbox_status = 'CLAIMED', submit_attempts = submit_attempts + 1,
            updated_at = CURRENT_TIMESTAMP
        FROM candidate c WHERE r.id = c.id
        RETURNING r.*
        """.trimIndent(),
        preparedRowMapper,
    ).singleOrNull()

    fun markSubmitted(runId: UUID, runtimeJobId: String, status: AnalysisStatus) {
        jdbc.update(
            """
            UPDATE analysis_run SET runtime_job_id = ?, status = ?, outbox_status = 'SUBMITTED',
                submitted_at = COALESCE(submitted_at, CURRENT_TIMESTAMP), error_code = NULL,
                error_message = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
            """.trimIndent(),
            runtimeJobId,
            status.name,
            runId,
        )
    }

    fun retrySubmit(runId: UUID, errorCode: String) {
        jdbc.update(
            """
            UPDATE analysis_run SET
                outbox_status = CASE WHEN submit_attempts >= 8 THEN 'FAILED' ELSE 'PENDING' END,
                status = CASE WHEN submit_attempts >= 8 THEN 'FAILED' ELSE 'PENDING' END,
                error_code = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
            errorCode,
            runId,
        )
    }

    fun activeRuns(limit: Int = 20): List<PreparedAnalysisRun> = jdbc.query(
        """
        SELECT * FROM analysis_run
        WHERE runtime_job_id IS NOT NULL AND status IN ('QUEUED', 'WAITING_FOR_WORKER', 'RUNNING')
        ORDER BY updated_at LIMIT ?
        """.trimIndent(),
        preparedRowMapper,
        limit,
    )

    fun completeSourceNotes(runId: UUID, result: JsonNode, completedAt: Instant) {
        jdbc.update(
            """
            UPDATE analysis_run SET status = 'SUCCEEDED', outbox_status = 'COMPLETE', phase_result = CAST(? AS jsonb),
                error_code = NULL, completed_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND run_type = 'SOURCE_NOTES'
            """.trimIndent(),
            mapper.writeValueAsString(result),
            Timestamp.from(completedAt),
            runId,
        )
    }

    fun readySynthesisRuns(limit: Int = 10): List<PreparedAnalysisRun> = jdbc.query(
        """
        SELECT final.* FROM analysis_run final
        WHERE final.run_type = 'FINAL_ADVICE' AND final.prompt_text IS NULL AND final.status = 'PENDING'
          AND EXISTS (SELECT 1 FROM analysis_run note WHERE note.parent_run_id = final.id)
          AND NOT EXISTS (
              SELECT 1 FROM analysis_run note WHERE note.parent_run_id = final.id AND note.status <> 'SUCCEEDED'
          )
        ORDER BY final.created_at LIMIT ?
        """.trimIndent(),
        preparedRowMapper,
        limit,
    )

    fun sourceNoteResults(parentRunId: UUID): List<JsonNode> = jdbc.query(
        """
        SELECT phase_result::text FROM analysis_run
        WHERE parent_run_id = ? AND run_type = 'SOURCE_NOTES' AND status = 'SUCCEEDED'
        ORDER BY phase_index
        """.trimIndent(),
        { rs, _ -> mapper.readTree(rs.getString(1)) },
        parentRunId,
    )

    fun activateSynthesis(runId: UUID, prompt: String) {
        jdbc.update(
            """
            UPDATE analysis_run SET prompt_text = ?, outbox_status = 'PENDING', updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND run_type = 'FINAL_ADVICE' AND prompt_text IS NULL AND status = 'PENDING'
            """.trimIndent(),
            prompt,
            runId,
        )
    }

    fun failParentFinal(parentRunId: UUID, errorCode: String) {
        jdbc.update(
            """
            UPDATE analysis_run SET status = 'FAILED', outbox_status = 'FAILED', error_code = ?,
                completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND run_type = 'FINAL_ADVICE' AND status = 'PENDING'
            """.trimIndent(),
            errorCode,
            parentRunId,
        )
    }

    fun updateRuntimeStatus(runId: UUID, status: AnalysisStatus, errorCode: String? = null) {
        jdbc.update(
            """
            UPDATE analysis_run SET status = ?, error_code = ?,
                outbox_status = CASE WHEN ? IN ('FAILED', 'CANCELLED') THEN 'FAILED' ELSE outbox_status END,
                completed_at = CASE WHEN ? IN ('FAILED', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE completed_at END,
                updated_at = CURRENT_TIMESTAMP WHERE id = ?
            """.trimIndent(),
            status.name,
            errorCode,
            status.name,
            status.name,
            runId,
        )
    }

    @org.springframework.transaction.annotation.Transactional
    fun completeWithAdvice(
        prepared: PreparedAnalysisRun,
        advice: JsonNode,
        citations: JsonNode,
        provider: String,
        model: String,
        completedAt: Instant,
    ) {
        saveAdvice(
            UUID.randomUUID(), prepared.run.id, prepared.run.agendaItemId, prepared.category,
            advice, citations, provider, model, prepared.run.promptVersion,
            prepared.run.sourceFingerprint, completedAt,
        )
        jdbc.update(
            """
            UPDATE analysis_run SET status = 'SUCCEEDED', outbox_status = 'COMPLETE', error_code = NULL,
                error_message = NULL, completed_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
            """.trimIndent(),
            Timestamp.from(completedAt),
            prepared.run.id,
        )
        jdbc.update(
            """
            UPDATE agenda_item_advice advice SET actuality = CASE
                WHEN (SELECT source_state FROM agenda_item WHERE id = ?) = 'WITHDRAWN' THEN 'WITHDRAWN'
                WHEN advice.analysis_run_id = (
                    SELECT id FROM analysis_run
                    WHERE agenda_item_id = ? AND run_type = 'FINAL_ADVICE'
                    ORDER BY created_at DESC, id DESC LIMIT 1
                ) THEN 'CURRENT'
                ELSE 'STALE'
            END
            WHERE advice.agenda_item_id = ?
            """.trimIndent(),
            prepared.run.agendaItemId,
            prepared.run.agendaItemId,
            prepared.run.agendaItemId,
        )
    }

    fun allRequiredRunsSucceeded(meetingId: UUID): Boolean = jdbc.queryForObject(
        """
        SELECT COUNT(*) > 0 AND NOT EXISTS (
            SELECT 1 FROM agenda_item ai
            WHERE ai.meeting_id = ? AND ai.source_state <> 'WITHDRAWN'
              AND ai.substantive AND ai.category IN ('A', 'B', 'C')
              AND NOT EXISTS (
                  SELECT 1 FROM agenda_item_advice advice
                  JOIN analysis_run run ON run.id = advice.analysis_run_id
                  WHERE advice.agenda_item_id = ai.id AND advice.actuality = 'CURRENT'
                    AND run.run_type = 'FINAL_ADVICE' AND run.status = 'SUCCEEDED'
              )
        )
        FROM analysis_run WHERE meeting_id = ?
        """.trimIndent(),
        Boolean::class.java,
        meetingId,
        meetingId,
    ) == true

    fun runControl(runId: UUID): RunControl? = jdbc.query(
        "SELECT id, meeting_id, runtime_job_id, status FROM analysis_run WHERE id = ?",
        { rs, _ ->
            RunControl(
                rs.getObject("id", UUID::class.java),
                rs.getObject("meeting_id", UUID::class.java),
                rs.getString("runtime_job_id"),
                AnalysisStatus.valueOf(rs.getString("status")),
            )
        },
        runId,
    ).singleOrNull()

    fun saveAdvice(
        id: UUID,
        runId: UUID,
        agendaItemId: UUID,
        category: String,
        advice: JsonNode,
        citations: JsonNode,
        provider: String,
        model: String,
        promptVersion: String,
        sourceFingerprint: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO agenda_item_advice(
                id, analysis_run_id, agenda_item_id, category, advice, citations, provider,
                model, prompt_version, source_fingerprint, created_at
            ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id) DO NOTHING
            """.trimIndent(),
            id,
            runId,
            agendaItemId,
            category,
            mapper.writeValueAsString(advice),
            mapper.writeValueAsString(citations),
            provider,
            model,
            promptVersion,
            sourceFingerprint,
            Timestamp.from(createdAt),
        )
    }

    private val preparedRowMapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        val run = AnalysisRun(
            id = rs.getObject("id", UUID::class.java),
            agendaItemId = rs.getObject("agenda_item_id", UUID::class.java),
            sourceFingerprint = rs.getString("source_fingerprint"),
            promptVersion = rs.getString("prompt_version"),
            selectionVersion = rs.getString("selection_version"),
            idempotencyKey = rs.getString("idempotency_key"),
            runtimeJobId = rs.getString("runtime_job_id"),
            status = AnalysisStatus.valueOf(rs.getString("status")),
            errorCode = rs.getString("error_code"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        )
        PreparedAnalysisRun(
            run = run,
            meetingId = rs.getObject("meeting_id", UUID::class.java),
            category = rs.getString("category"),
            agendaItemSourceId = rs.getString("agenda_item_source_id"),
            prompt = rs.getString("prompt_text"),
            responseSchema = mapper.readTree(rs.getString("response_schema")),
            allowedSources = mapper.readValue(rs.getString("allowed_sources"), object : TypeReference<List<AnalysisSource>>() {}),
            runType = AnalysisRunType.valueOf(rs.getString("run_type")),
            phaseIndex = rs.getInt("phase_index"),
            parentRunId = rs.getObject("parent_run_id", UUID::class.java),
        )
    }
}
