package nl.vdzon.pvdd.analysis

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class AnalysisRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
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
}
