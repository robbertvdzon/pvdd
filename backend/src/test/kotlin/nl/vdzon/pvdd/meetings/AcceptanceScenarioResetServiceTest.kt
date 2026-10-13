package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
class AcceptanceScenarioResetServiceTest {
    private val source = MeetingSourceProperties(URI("http://pvdd-meeting-source-mock:8080"), "acceptance")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var datasource: DriverManagerDataSource
    private lateinit var locks: WorkflowLockRepository
    private lateinit var service: AcceptanceScenarioResetService

    @BeforeEach
    fun prepare() {
        datasource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(datasource).load().migrate()
        jdbc = JdbcTemplate(datasource)
        jdbc.execute("TRUNCATE meeting CASCADE")
        jdbc.execute("DELETE FROM workflow_lock")
        locks = WorkflowLockRepository(jdbc)
        service = AcceptanceScenarioResetService(jdbc, locks, source, DataSourceTransactionManager(datasource))
    }

    @Test
    fun `reset deletes only recognized mock fixtures with all dependent history`() {
        val fixture = insertMeeting("scn-twee-vergaderingen-123-1")
        insertMeeting("acceptance-meeting-v1")
        insertHistory(fixture)
        val foreignOrigin = insertMeeting("scn-twee-vergaderingen-123-2", "https://real.example/Agenda/Index/scn-twee-vergaderingen-123-2")
        val unknownScenario = insertMeeting("scn-other-purpose-1-1")
        val ordinary = insertMeeting("real-meeting")
        val metadata = jdbc.queryForList("SELECT * FROM application_metadata ORDER BY metadata_key")

        assertEquals(AcceptanceResetResult(deletedMeetings = 2, preservedMeetings = 3), service.reset())
        assertEquals(setOf(foreignOrigin, unknownScenario, ordinary), jdbc.query("SELECT id FROM meeting", { rs, _ -> rs.getObject(1, UUID::class.java) }).toSet())
        DEPENDENT_TABLES.forEach { assertEquals(0, count(it), it) }
        assertEquals(metadata, jdbc.queryForList("SELECT * FROM application_metadata ORDER BY metadata_key"))
        assertEquals(AcceptanceResetResult(deletedMeetings = 0, preservedMeetings = 3), service.reset())
    }

    @Test
    fun `pending and claimed meeting analysis prevents reset and preserves all data`() {
        listOf("PENDING", "CLAIMED").forEachIndexed { index, status ->
            val meeting = insertMeeting("scn-twee-vergaderingen-${index + 1}-1")
            jdbc.update("INSERT INTO analysis_meeting_queue(meeting_id,status) VALUES (?,?)", meeting, status)
            val before = jdbc.queryForList("SELECT * FROM meeting ORDER BY id")
            assertEquals(409, assertFailsWith<ResponseStatusException> { service.reset() }.statusCode.value())
            assertEquals(before, jdbc.queryForList("SELECT * FROM meeting ORDER BY id"))
            jdbc.update("UPDATE analysis_meeting_queue SET status='COMPLETE' WHERE meeting_id=?", meeting)
        }
        assertEquals(2, service.reset().deletedMeetings)
    }

    @Test
    fun `active analysis and claimed outbox prevent reset until the run is terminal`() {
        val meeting = insertMeeting("scn-twee-vergaderingen-1-1")
        val item = insertItem(meeting)
        val run = insertRun(meeting, item)
        listOf("PENDING", "QUEUED", "WAITING_FOR_WORKER", "RUNNING").forEach { status ->
            jdbc.update("UPDATE analysis_run SET status=? WHERE id=?", status, run)
            assertEquals(409, assertFailsWith<ResponseStatusException> { service.reset() }.statusCode.value())
            assertEquals(1, count("meeting"))
            assertEquals(1, count("analysis_run"))
        }
        jdbc.update("UPDATE analysis_run SET status='FAILED',outbox_status='CLAIMED' WHERE id=?", run)
        assertEquals(409, assertFailsWith<ResponseStatusException> { service.reset() }.statusCode.value())
        jdbc.update("UPDATE analysis_run SET outbox_status='FAILED' WHERE id=?", run)
        assertEquals(1, service.reset().deletedMeetings)
    }

    @Test
    fun `active meeting check prevents reset without releasing another workflow owner`() {
        insertMeeting("scn-twee-vergaderingen-1-1")
        val owner = UUID.randomUUID()
        assertTrue(locks.tryAcquire(MeetingCheckWorkflow.WORKFLOW_LOCK, owner))
        assertEquals(409, assertFailsWith<ResponseStatusException> { service.reset() }.statusCode.value())
        assertEquals(owner, jdbc.queryForObject("SELECT owner_id FROM workflow_lock WHERE lock_name=?", UUID::class.java, MeetingCheckWorkflow.WORKFLOW_LOCK))
        assertEquals(1, count("meeting"))
        locks.release(MeetingCheckWorkflow.WORKFLOW_LOCK, owner)
        assertEquals(1, service.reset().deletedMeetings)
    }

    @Test
    fun `concurrent queue or run writer refuses reset instead of racing the active work check`() {
        insertMeeting("scn-twee-vergaderingen-1-1")
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writer = executor.submit {
            datasource.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("LOCK TABLE analysis_run IN ROW EXCLUSIVE MODE") }
                acquired.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                connection.commit()
            }
        }
        try {
            assertTrue(acquired.await(10, TimeUnit.SECONDS))
            assertEquals(409, assertFailsWith<ResponseStatusException> { service.reset() }.statusCode.value())
            assertEquals(1, count("meeting"))
        } finally {
            release.countDown()
            writer.get(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
        assertEquals(1, service.reset().deletedMeetings)
    }

    @Test
    fun `entire source failure preserves previously imported meetings documents and advice`() {
        val meeting = insertMeeting("scn-twee-vergaderingen-1-1")
        insertHistory(meeting)
        val before = (listOf("meeting") + DEPENDENT_TABLES).associateWith(::rowsAsJson)
        val now = Instant.parse("2026-10-13T12:00:00Z")
        val discovery = mock(MeetingDiscoveryGateway::class.java)
        `when`(discovery.discover(now)).thenReturn(listOf(DiscoveryOutcome.SourceFailure(SourceErrorCode.HTTP_ERROR)))
        val documents = mock(DocumentIngestor::class.java)
        val events = mock(ApplicationEventPublisher::class.java)
        val workflow = MeetingCheckWorkflow(discovery, MeetingRepository(jdbc), documents, locks, events, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(MeetingCheckStatus.SOURCE_FAILURE, workflow.check().status)
        before.forEach { (table, rows) -> assertEquals(rows, rowsAsJson(table), table) }
        verifyNoInteractions(documents, events)
    }

    private fun insertMeeting(sourceId: String, sourceUrl: String = "${source.baseUrl}/Agenda/Index/$sourceId"): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO meeting(id,source_id,committee,starts_at,title,source_url,source_hash,status,checked_at)
               VALUES (?,?,'Synthetic',CURRENT_TIMESTAMP + INTERVAL '1 day','Synthetic meeting',?,?,'COMPLETE',CURRENT_TIMESTAMP)""",
            id, sourceId, sourceUrl, "a".repeat(64),
        )
        return id
    }

    private fun insertItem(meeting: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO agenda_item(id,meeting_id,source_id,sequence_number,category,title,source_url,source_hash,substantive,import_status)
               VALUES (?,?,'item-housing',1,'A','Synthetic item','http://pvdd-meeting-source-mock:8080/item',?,TRUE,'COMPLETE')""",
            id, meeting, "b".repeat(64),
        )
        return id
    }

    private fun insertRun(meeting: UUID, item: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO analysis_run(id,meeting_id,agenda_item_id,source_fingerprint,prompt_version,selection_version,
               idempotency_key,status,outbox_status,created_at,updated_at)
               VALUES (?,?,?,?,'v1','v1',?,'SUCCEEDED','COMPLETE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""",
            id, meeting, item, "c".repeat(64), id.toString(),
        )
        return id
    }

    private fun insertHistory(meeting: UUID) {
        val item = insertItem(meeting)
        val run = insertRun(meeting, item)
        val child = insertRun(meeting, item)
        jdbc.update("UPDATE analysis_run SET parent_run_id=?,run_type='SOURCE_NOTES',phase_index=1 WHERE id=?", run, child)
        jdbc.update(
            """INSERT INTO agenda_item_advice(id,analysis_run_id,agenda_item_id,category,advice,citations,provider,model,
               prompt_version,source_fingerprint,created_at) VALUES (?,?,?,'A','{}','[]','MOCKED','mock','v1',?,CURRENT_TIMESTAMP)""",
            UUID.randomUUID(), run, item, "c".repeat(64),
        )
        jdbc.update(
            """INSERT INTO source_document(id,agenda_item_id,source_id,name,source_url,extraction_status)
               VALUES (?,?,'doc-housing','Synthetic document','http://pvdd-meeting-source-mock:8080/doc','EXTRACTED')""",
            UUID.randomUUID(), item,
        )
        val revision = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO meeting_revision(id,meeting_id,revision_number,publication_status,revision_status,canonical_fingerprint,source_url,checked_at)
               VALUES (?,?,1,'CURRENT','CURRENT',?,'http://pvdd-meeting-source-mock:8080/agenda',CURRENT_TIMESTAMP)""",
            revision, meeting, "d".repeat(64),
        )
        val itemRevision = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO agenda_item_revision(id,meeting_revision_id,agenda_item_id,source_id,sequence_number,category,title,source_url,item_fingerprint,source_state)
               VALUES (?,?,?,'item-housing',1,'A','Synthetic item','http://pvdd-meeting-source-mock:8080/item',?,'CURRENT')""",
            itemRevision, revision, item, "e".repeat(64),
        )
        jdbc.update(
            """INSERT INTO document_revision(id,agenda_item_revision_id,source_id,name,source_url,source_state)
               VALUES (?,?,'doc-housing','Synthetic document','http://pvdd-meeting-source-mock:8080/doc','CURRENT')""",
            UUID.randomUUID(), itemRevision,
        )
        jdbc.update(
            """INSERT INTO source_check(id,meeting_id,checked_at,publication_status,canonical_fingerprint,outcome)
               VALUES (?,?,CURRENT_TIMESTAMP,'CURRENT',?,'UNCHANGED')""",
            UUID.randomUUID(), meeting, "f".repeat(64),
        )
        jdbc.update("INSERT INTO analysis_meeting_queue(meeting_id,status) VALUES (?,'COMPLETE')", meeting)
    }

    private fun rowsAsJson(table: String) = jdbc.queryForList("SELECT to_jsonb(row)::text FROM $table row ORDER BY to_jsonb(row)::text", String::class.java)

    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        private val DEPENDENT_TABLES = listOf("agenda_item", "source_document", "analysis_run", "agenda_item_advice", "analysis_meeting_queue", "meeting_revision", "source_check", "agenda_item_revision", "document_revision")
    }
}
