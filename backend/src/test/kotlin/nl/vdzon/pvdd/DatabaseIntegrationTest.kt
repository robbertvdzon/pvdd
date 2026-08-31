package nl.vdzon.pvdd

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisRepository
import nl.vdzon.pvdd.analysis.AnalysisRun
import nl.vdzon.pvdd.analysis.AnalysisStatus
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.documents.ExtractedSection
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.documents.SourceDocument
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaItem
import nl.vdzon.pvdd.meetings.ImportStatus
import nl.vdzon.pvdd.meetings.Meeting
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.MeetingStatus
import nl.vdzon.pvdd.persistence.ApplicationMetadataRepository
import nl.vdzon.pvdd.policy.PolicyChunk
import nl.vdzon.pvdd.policy.PolicySourceRepository
import nl.vdzon.pvdd.policy.PolicyTheme
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest
class DatabaseIntegrationTest(
    @param:Autowired private val metadataRepository: ApplicationMetadataRepository,
    @param:Autowired private val meetingRepository: MeetingRepository,
    @param:Autowired private val documentRepository: DocumentRepository,
    @param:Autowired private val analysisRepository: AnalysisRepository,
    @param:Autowired private val policySourceRepository: PolicySourceRepository,
    @param:Autowired private val healthEndpoint: HealthEndpoint,
) {
    @Test
    fun `empty PostgreSQL is migrated and metadata survives writes`() {
        assertEquals("PvdD technical baseline", metadataRepository.get("schema-purpose"))
        metadataRepository.put("integration-test", "works")
        assertEquals("works", metadataRepository.get("integration-test"))
        assertEquals("UP", healthEndpoint.health().status.code)
    }

    @Test
    fun `functional import is idempotent and only success advances the checkpoint`() {
        val now = Instant.parse("2026-08-31T20:00:00Z")
        val meetingId = UUID.randomUUID()
        val meeting = Meeting(
            id = meetingId,
            sourceId = "meeting-functional-test",
            committee = "Commissie Ruimte",
            startsAt = Instant.parse("2026-09-14T16:30:00Z"),
            endsAt = Instant.parse("2026-09-14T20:30:00Z"),
            location = "Statenzaal",
            title = "Commissie Ruimte 14 september 2026",
            sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-functional-test"),
            sourceHash = "a".repeat(64),
            status = MeetingStatus.IMPORTING,
            checkedAt = now,
            importedAt = now,
        )
        assertEquals(meetingId, meetingRepository.upsert(meeting))
        assertEquals(meetingId, meetingRepository.upsert(meeting.copy(title = "Bijgewerkte titel")))
        assertEquals(1, meetingRepository.countMeetingsBySourceId(meeting.sourceId))

        val itemId = UUID.randomUUID()
        val item = AgendaItem(
            id = itemId,
            meetingId = meetingId,
            sourceId = "item-a",
            parentSourceId = "section-a",
            sequence = 1,
            displayNumber = "1.a",
            category = AgendaCategory.A,
            title = "Natuurinclusieve woningen",
            explanation = "Synthetische toelichting",
            treatmentProposal = "Bespreken",
            sourceUrl = meeting.sourceUrl,
            sourceHash = "b".repeat(64),
            substantive = true,
            importStatus = ImportStatus.COMPLETE,
        )
        assertEquals(itemId, meetingRepository.upsert(item))
        assertEquals(itemId, meetingRepository.upsert(item.copy(title = "Bijgewerkt woonvoorstel")))
        assertEquals(1, meetingRepository.countAgendaItems(meetingId))

        val originalDocument = document(itemId, "c".repeat(64), now)
        assertTrue(documentRepository.insertVersion(originalDocument))
        assertFalse(documentRepository.insertVersion(originalDocument.copy(id = UUID.randomUUID())))
        assertTrue(documentRepository.insertVersion(document(itemId, "d".repeat(64), now.plusSeconds(1))))
        assertEquals(2, documentRepository.countVersions(itemId, "doc-a"))

        val failedDocument = document(itemId, "e".repeat(64), now).copy(
            id = UUID.randomUUID(),
            sourceId = "doc-failed",
            sha256 = null,
            sizeBytes = null,
            fetchedAt = null,
            extractionStatus = ExtractionStatus.DOWNLOAD_FAILED,
            errorCode = "TIMEOUT",
            sections = emptyList(),
        )
        assertTrue(documentRepository.save(failedDocument))
        assertTrue(documentRepository.save(failedDocument.copy(id = UUID.randomUUID(), errorCode = "HTTP_ERROR")))
        assertEquals(1, documentRepository.countVersions(itemId, "doc-failed"))

        val run = AnalysisRun(
            id = UUID.randomUUID(),
            agendaItemId = itemId,
            sourceFingerprint = "e".repeat(64),
            promptVersion = "advice-v1",
            selectionVersion = "policy-v1",
            idempotencyKey = "pvdd:meeting-functional-test:item-a:${"e".repeat(64)}:advice-v1",
            runtimeJobId = null,
            status = AnalysisStatus.PENDING,
            errorCode = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        assertEquals(run.id, analysisRepository.createRun(meetingId, run))
        assertEquals(run.id, analysisRepository.createRun(meetingId, run.copy(id = UUID.randomUUID())))

        val policy = PolicyChunk(
            id = UUID.randomUUID(),
            sourceUrl = URI("https://example.invalid/policy.pdf"),
            sourceSha256 = "f".repeat(64),
            fetchedAt = now,
            pageNumber = 1,
            sequence = 1,
            heading = "Natuur",
            text = "Synthetische beleidstekst",
            themes = setOf(PolicyTheme.ANIMALS_AND_NATURE),
        )
        assertTrue(policySourceRepository.insert(policy))
        assertFalse(policySourceRepository.insert(policy.copy(id = UUID.randomUUID())))
        assertEquals(1, policySourceRepository.countByHash(policy.sourceSha256))

        meetingRepository.markSuccessful(meetingId)
        assertEquals(meeting.sourceId, meetingRepository.lastSuccessfulSourceId())

        val failedId = UUID.randomUUID()
        meetingRepository.upsert(meeting.copy(id = failedId, sourceId = "meeting-failed", status = MeetingStatus.IMPORTING))
        meetingRepository.markFailed(failedId, "DOCUMENT_INVALID")
        assertEquals(meeting.sourceId, meetingRepository.lastSuccessfulSourceId())
    }

    private fun document(itemId: UUID, hash: String, fetchedAt: Instant) = SourceDocument(
        id = UUID.randomUUID(),
        agendaItemId = itemId,
        sourceId = "doc-a",
        name = "Synthetisch document",
        sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Document/meeting-functional-test?documentId=doc-a"),
        declaredMimeType = "text/plain",
        detectedMimeType = "text/plain",
        sha256 = hash,
        sizeBytes = 32,
        extractionStatus = ExtractionStatus.EXTRACTED,
        fetchedAt = fetchedAt,
        errorCode = null,
        sections = listOf(ExtractedSection(1, 1, null, "Synthetische documenttekst")),
    )

    companion object {
        @Container @ServiceConnection @JvmField
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
