package nl.vdzon.pvdd.meetings

import java.util.UUID
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

/** Removes only recognised acceptance fixtures, never accounts, policy or arbitrary imported data. */
@Service
@ConditionalOnProperty(name = ["pvdd.environment"], havingValue = "acceptance")
class AcceptanceScenarioResetService(
    private val jdbc: JdbcTemplate,
    private val locks: WorkflowLock,
    private val source: MeetingSourceProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val named = NamedParameterJdbcTemplate(jdbc)

    fun reset(): AcceptanceResetResult {
        val owner = UUID.randomUUID()
        if (!locks.tryAcquire(MeetingCheckWorkflow.WORKFLOW_LOCK, owner)) busy()
        return try {
            transactions.execute {
                // The workflow lease excludes source imports. Table locks exclude queue claims,
                // new analysis runs and retries between the active-work check and deletion.
                jdbc.execute("LOCK TABLE $FIXTURE_TABLES IN SHARE ROW EXCLUSIVE MODE NOWAIT")
                val candidates = jdbc.query(
                    "SELECT id,source_id,source_url FROM meeting WHERE source_id='acceptance-meeting-v1' OR source_id ~ ?",
                    { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getString("source_id"), rs.getString("source_url")) },
                    SYNTHETIC_ID,
                ).filter { (_, id, url) -> url == source.baseUrl.toString().trimEnd('/') + "/Agenda/Index/$id" }
                val ids = candidates.map { it.first }
                if (ids.isNotEmpty()) {
                    val params = mapOf("ids" to ids)
                    val activeQueue = named.queryForObject(
                        "SELECT COUNT(*) FROM analysis_meeting_queue WHERE meeting_id IN (:ids) AND status IN ('PENDING','CLAIMED')",
                        params, Long::class.java,
                    ) ?: 0
                    val activeRuns = named.queryForObject(
                        """SELECT COUNT(*) FROM analysis_run WHERE meeting_id IN (:ids)
                           AND (status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR outbox_status='CLAIMED')""",
                        params, Long::class.java,
                    ) ?: 0
                    if (activeQueue > 0 || activeRuns > 0) busy()
                    val items = "SELECT id FROM agenda_item WHERE meeting_id IN (:ids)"
                    val revisions = "SELECT id FROM meeting_revision WHERE meeting_id IN (:ids)"
                    val itemRevisions = "SELECT id FROM agenda_item_revision WHERE meeting_revision_id IN ($revisions)"
                    listOf(
                        "DELETE FROM agenda_item_advice WHERE agenda_item_id IN ($items)",
                        "DELETE FROM analysis_run WHERE meeting_id IN (:ids)",
                        "DELETE FROM document_revision WHERE agenda_item_revision_id IN ($itemRevisions)",
                        "DELETE FROM agenda_item_revision WHERE meeting_revision_id IN ($revisions)",
                        "DELETE FROM meeting_revision WHERE meeting_id IN (:ids)",
                        "DELETE FROM source_check WHERE meeting_id IN (:ids)",
                        "DELETE FROM source_document WHERE agenda_item_id IN ($items)",
                        "DELETE FROM analysis_meeting_queue WHERE meeting_id IN (:ids)",
                        "DELETE FROM agenda_item WHERE meeting_id IN (:ids)",
                        "DELETE FROM meeting WHERE id IN (:ids)",
                    ).forEach { named.update(it, params) }
                }
                AcceptanceResetResult(
                    deletedMeetings = ids.size,
                    preservedMeetings = jdbc.queryForObject("SELECT COUNT(*) FROM meeting", Int::class.java) ?: 0,
                )
            } ?: error("Missing reset result")
        } catch (_: PessimisticLockingFailureException) {
            busy()
        } catch (failure: UncategorizedSQLException) {
            // PostgreSQL NOWAIT is not classified by every Spring SQL translator.
            if (failure.sqlException?.sqlState == "55P03") busy()
            throw failure
        } finally {
            locks.release(MeetingCheckWorkflow.WORKFLOW_LOCK, owner)
        }
    }

    private fun busy(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "acceptance_work_active")

    companion object {
        private const val SYNTHETIC_ID = "^scn-(twee-vergaderingen|meer-dan-drie|grens-dag-10-en-11|extra-agenda-niet-gepubliceerd|een-vergadering-mislukt)-[0-9]+-[0-9]+$"
        private const val FIXTURE_TABLES = "meeting, agenda_item, source_document, analysis_meeting_queue, analysis_run, agenda_item_advice, meeting_revision, source_check, agenda_item_revision, document_revision"
    }
}

data class AcceptanceResetResult(
    val mode: String = "synthetic-only",
    val deletedMeetings: Int,
    val preservedMeetings: Int,
)
