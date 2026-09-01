package nl.vdzon.pvdd

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisRepository
import nl.vdzon.pvdd.analysis.AnalysisRun
import nl.vdzon.pvdd.analysis.AnalysisRunType
import nl.vdzon.pvdd.analysis.AnalysisStatus
import nl.vdzon.pvdd.analysis.AnalysisSource
import nl.vdzon.pvdd.analysis.CitationSourceType
import nl.vdzon.pvdd.analysis.PreparedAnalysisRun
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.documents.ExtractedSection
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.documents.SourceDocument
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaItem
import nl.vdzon.pvdd.meetings.ImportStatus
import nl.vdzon.pvdd.meetings.Meeting
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.MeetingStatus
import nl.vdzon.pvdd.meetings.AgendaParser
import nl.vdzon.pvdd.meetings.AgendaRevisionComparator
import nl.vdzon.pvdd.meetings.DifferenceType
import nl.vdzon.pvdd.meetings.PublicationStatus
import nl.vdzon.pvdd.meetings.RevisionDocument
import nl.vdzon.pvdd.meetings.SourceRevisionRepository
import nl.vdzon.pvdd.meetings.SourceState
import nl.vdzon.pvdd.meetings.WorkflowLockRepository
import nl.vdzon.pvdd.persistence.ApplicationMetadataRepository
import nl.vdzon.pvdd.policy.PolicyChunk
import nl.vdzon.pvdd.policy.PolicySourceRepository
import nl.vdzon.pvdd.policy.PolicyTheme
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.module.kotlin.jacksonObjectMapper

@Testcontainers
@SpringBootTest
class DatabaseIntegrationTest(
    @param:Autowired private val metadataRepository: ApplicationMetadataRepository,
    @param:Autowired private val meetingRepository: MeetingRepository,
    @param:Autowired private val documentRepository: DocumentRepository,
    @param:Autowired private val analysisRepository: AnalysisRepository,
    @param:Autowired private val policySourceRepository: PolicySourceRepository,
    @param:Autowired private val workflowLockRepository: WorkflowLockRepository,
    @param:Autowired private val sourceRevisionRepository: SourceRevisionRepository,
    @param:Autowired private val revisionComparator: AgendaRevisionComparator,
    @param:Autowired private val jdbc: JdbcTemplate,
    @param:Autowired private val dashboardRepository: DashboardRepository,
    @param:Autowired private val healthEndpoint: HealthEndpoint,
    @param:Autowired private val userSessionService: UserSessionService,
) {
    @Test
    fun `empty PostgreSQL is migrated and metadata survives writes`() {
        assertEquals("PvdD technical baseline", metadataRepository.get("schema-purpose"))
        metadataRepository.put("integration-test", "works")
        assertEquals("works", metadataRepository.get("integration-test"))
        assertEquals("UP", healthEndpoint.health().status.code)
    }

    @Test
    fun `user session stores only a hash and can be revoked`() {
        val session = userSessionService.create("robbertvdzon@gmail.com")
        assertEquals("robbertvdzon@gmail.com", userSessionService.authenticate(session.token).email)
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE token_hash = ?",
                Int::class.java,
                session.token,
            ),
        )
        userSessionService.revoke(session.token)
        org.junit.jupiter.api.assertThrows<ResponseStatusException> {
            userSessionService.authenticate(session.token)
        }
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
        assertTrue(documentRepository.findPassagesForAnalysis(itemId).isEmpty())

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

        analysisRepository.queueMeeting(meetingId)
        assertEquals(meetingId, analysisRepository.claimMeeting())
        analysisRepository.finishMeetingPreparation(meetingId)

        val mapper = jacksonObjectMapper()
        val prepared = PreparedAnalysisRun(
            run = run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"9".repeat(64)}",
                createdAt = now.plusSeconds(1),
                updatedAt = now.plusSeconds(1),
            ),
            meetingId = meetingId,
            category = "A",
            agendaItemSourceId = item.sourceId,
            prompt = "synthetic durable prompt",
            responseSchema = mapper.readTree("""{"type":"object"}"""),
            allowedSources = listOf(
                AnalysisSource(
                    sourceId = "policy-p1-c1",
                    sourceType = CitationSourceType.POLICY_PROGRAMME,
                    sourceUrl = policyUrl,
                    pageNumber = 1,
                    section = "Natuur",
                    text = "Synthetische beleidstekst",
                ),
            ),
        )
        val preparedId = analysisRepository.createPreparedRun(prepared)
        val firstClaim = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(preparedId, firstClaim.run.id)
        analysisRepository.retrySubmit(preparedId, "LOST_RESPONSE")
        val recoveredClaim = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(prepared.run.idempotencyKey, recoveredClaim.run.idempotencyKey)
        assertEquals(prepared.allowedSources, recoveredClaim.allowedSources)
        analysisRepository.markSubmitted(preparedId, "runtime-job-1", AnalysisStatus.RUNNING)
        assertEquals(preparedId, analysisRepository.activeRuns().single { it.run.id == preparedId }.run.id)
        analysisRepository.completeWithAdvice(
            recoveredClaim,
            mapper.readTree("""{"validated":true}"""),
            mapper.createArrayNode(),
            "MOCKED",
            "mock-model",
            now.plusSeconds(2),
        )
        assertTrue(analysisRepository.allRequiredRunsSucceeded(meetingId))

        val newer = prepared.copy(
            run = prepared.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"6".repeat(64)}",
                createdAt = now.plusSeconds(4),
                updatedAt = now.plusSeconds(4),
            ),
            prompt = "newer synthetic prompt",
        )
        analysisRepository.createPreparedRun(newer)
        analysisRepository.completeWithAdvice(
            recoveredClaim,
            mapper.readTree("""{"validated":"late-old-result"}"""),
            mapper.createArrayNode(),
            "MOCKED",
            "mock-model",
            now.plusSeconds(5),
        )
        assertEquals("STALE", jdbc.queryForObject(
            "SELECT actuality FROM agenda_item_advice WHERE analysis_run_id = ?",
            String::class.java,
            preparedId,
        ))
        analysisRepository.completeWithAdvice(
            newer,
            mapper.readTree("""{"validated":"new-result"}"""),
            mapper.createArrayNode(),
            "MOCKED",
            "mock-model",
            now.plusSeconds(6),
        )
        assertEquals("CURRENT", jdbc.queryForObject(
            "SELECT actuality FROM agenda_item_advice WHERE analysis_run_id = ?",
            String::class.java,
            newer.run.id,
        ))

        val laterFailed = newer.copy(
            run = newer.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"5".repeat(64)}",
                createdAt = now.plusSeconds(7),
                updatedAt = now.plusSeconds(7),
            ),
        )
        analysisRepository.createPreparedRun(laterFailed)
        jdbc.update(
            "UPDATE analysis_run SET status = 'FAILED', outbox_status = 'FAILED' WHERE id = ?",
            laterFailed.run.id,
        )
        assertFalse(analysisRepository.allRequiredRunsSucceeded(meetingId))

        assertEquals(preparedId, analysisRepository.createPreparedRun(prepared))
        assertTrue(analysisRepository.allRequiredRunsSucceeded(meetingId))
        assertEquals("CURRENT", jdbc.queryForObject(
            "SELECT actuality FROM agenda_item_advice WHERE analysis_run_id = ?",
            String::class.java,
            preparedId,
        ))
        assertEquals(
            "CURRENT",
            requireNotNull(dashboardRepository.agendaItems(meetingId))
                .single { it.id == itemId }
                .adviceActuality,
        )
        assertEquals("CURRENT", requireNotNull(dashboardRepository.item(itemId)).adviceActuality)

        val phasedFinal = prepared.copy(
            run = prepared.run.copy(id = UUID.randomUUID(), idempotencyKey = "pvdd-${"7".repeat(64)}"),
            prompt = null,
        )
        val noteRun = prepared.copy(
            run = prepared.run.copy(id = UUID.randomUUID(), idempotencyKey = "pvdd-${"8".repeat(64)}-notes-1"),
            prompt = "durable source notes prompt",
            runType = AnalysisRunType.SOURCE_NOTES,
            phaseIndex = 1,
            parentRunId = phasedFinal.run.id,
        )
        analysisRepository.createPhasedRuns(phasedFinal, listOf(noteRun))
        val claimedNote = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(AnalysisRunType.SOURCE_NOTES, claimedNote.runType)
        analysisRepository.markSubmitted(claimedNote.run.id, "runtime-notes-1", AnalysisStatus.RUNNING)
        analysisRepository.completeSourceNotes(
            claimedNote.run.id,
            mapper.readTree("""{"content":"Synthetische feitelijke bronnotitie."}"""),
            now.plusSeconds(3),
        )
        val readyFinal = analysisRepository.readySynthesisRuns().single { it.run.id == phasedFinal.run.id }
        assertEquals(1, analysisRepository.sourceNoteResults(readyFinal.run.id).size)
        analysisRepository.activateSynthesis(readyFinal.run.id, "restart-safe synthesis prompt")
        val claimedFinal = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(phasedFinal.run.id, claimedFinal.run.id)
        assertEquals(AnalysisRunType.FINAL_ADVICE, claimedFinal.runType)

        val policy = PolicyChunk(
            id = UUID.randomUUID(),
            sourceUrl = policyUrl,
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

    @Test
    fun `source revisions retain preview and published history without duplicating unchanged snapshots`() {
        val now = Instant.parse("2026-09-01T05:00:00Z")
        val sourceId = "meeting-source-revision-test"
        val sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/$sourceId")
        val parsed = AgendaParser().parse(
            requireNotNull(javaClass.getResource("/fixtures/meetings/agenda-full.html")).readText(),
            sourceUrl,
        )
        val meetingId = meetingRepository.upsert(
            Meeting(
                UUID.randomUUID(), sourceId, parsed.committee, parsed.startsAt, parsed.endsAt, parsed.location,
                parsed.title, sourceUrl, parsed.sourceHash, MeetingStatus.AGENDA_UNPUBLISHED, now, null,
                PublicationStatus.PREVIEW,
            ),
        )
        val previewIds = parsed.items.filter { it.substantive && it.category == AgendaCategory.C }.associate { cItem ->
            cItem.sourceId to meetingRepository.upsert(
                AgendaItem(
                    UUID.randomUUID(), meetingId, cItem.sourceId, cItem.parentSourceId, cItem.sequence,
                    cItem.displayNumber, cItem.category, cItem.title, cItem.explanation, cItem.treatmentProposal,
                    cItem.sourceUrl, cItem.sourceHash, true, ImportStatus.PENDING, SourceState.PREVIEW,
                ),
            )
        }
        val previewItems = parsed.items
            .filter { it.substantive && it.category == AgendaCategory.C }
            .map { item ->
                revisionComparator.currentItem(
                    item,
                    requireNotNull(previewIds[item.sourceId]),
                    emptyList(),
                    SourceState.PREVIEW,
                )
            }
        val previewComparison = revisionComparator.compare(parsed, PublicationStatus.PREVIEW, previewItems, null)
        val preview = sourceRevisionRepository.record(
            meetingId, parsed, PublicationStatus.PREVIEW, previewItems, previewComparison, now,
        )
        assertEquals(1, preview.number)

        val currentItems = parsed.items.filter { it.substantive }.map { item ->
            val itemId = meetingRepository.upsert(
                AgendaItem(
                    UUID.randomUUID(), meetingId, item.sourceId, item.parentSourceId, item.sequence,
                    item.displayNumber, item.category, item.title, item.explanation, item.treatmentProposal,
                    item.sourceUrl, item.sourceHash, true, ImportStatus.COMPLETE, SourceState.CURRENT,
                ),
            )
            revisionComparator.currentItem(
                item,
                itemId,
                listOf(
                    SourceDocument(
                        UUID.randomUUID(), itemId, "document-${item.sourceId}", "Stuk ${item.sourceId}",
                        URI("https://noordholland.bestuurlijkeinformatie.nl/Document/View/${item.sourceId}"),
                        "application/pdf", "application/pdf", "a".repeat(64), 42,
                        ExtractionStatus.EXTRACTED, now, null, emptyList(),
                    ),
                ),
            )
        }
        val publishedComparison = revisionComparator.compare(
            parsed, PublicationStatus.CURRENT, currentItems, sourceRevisionRepository.baseline(sourceId),
        )
        assertTrue(DifferenceType.PUBLICATION_STATUS in publishedComparison.differences)
        val published = sourceRevisionRepository.record(
            meetingId, parsed, PublicationStatus.CURRENT, currentItems, publishedComparison, now.plusSeconds(60),
        )
        assertEquals(2, published.number)

        val unchangedComparison = revisionComparator.compare(
            parsed, PublicationStatus.CURRENT, currentItems, sourceRevisionRepository.baseline(sourceId),
        )
        assertTrue(unchangedComparison.unchanged)
        assertEquals(
            2,
            sourceRevisionRepository.record(
                meetingId, parsed, PublicationStatus.CURRENT, currentItems, unchangedComparison, now.plusSeconds(120),
            ).number,
        )
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_revision WHERE meeting_id = ?", Int::class.java, meetingId))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM source_check WHERE meeting_id = ?", Int::class.java, meetingId))
        assertEquals(previewItems.size, jdbc.queryForObject(
            "SELECT COUNT(*) FROM agenda_item_revision WHERE meeting_revision_id = ? AND source_state = 'PREVIEW'",
            Int::class.java,
            preview.id,
        ))
        requireNotNull(dashboardRepository.overview().meeting)
        val dashboardItems = requireNotNull(dashboardRepository.agendaItems(meetingId))
        assertTrue(dashboardItems.any { it.sourceState == SourceState.CURRENT.name && it.changeTypes.isNotEmpty() })
        assertEquals(
            SourceState.CURRENT.name,
            requireNotNull(dashboardRepository.item(dashboardItems.first().id)).item.sourceState,
        )

        val target = currentItems.first()
        val currentDocument = SourceDocument(
            UUID.randomUUID(), target.agendaItemId, target.documents.single().sourceId,
            target.documents.single().name, target.documents.single().sourceUrl,
            "text/plain", "text/plain", target.documents.single().sha256,
            target.documents.single().sizeBytes, ExtractionStatus.EXTRACTED, now.plusSeconds(180), null,
            listOf(ExtractedSection(1, 1, null, "Actuele synthetische documenttekst")),
        )
        assertTrue(documentRepository.insertVersion(currentDocument))
        assertEquals(
            "Actuele synthetische documenttekst",
            documentRepository.findPassagesForAnalysis(target.agendaItemId).single().text,
        )

        val parsedTarget = parsed.items.single { it.sourceId == target.sourceId }
        val withoutDocument = currentItems.map { item ->
            if (item.sourceId == target.sourceId) {
                revisionComparator.currentItem(parsedTarget, target.agendaItemId, emptyList())
            } else {
                item
            }
        }
        val removedComparison = revisionComparator.compare(
            parsed, PublicationStatus.CURRENT, withoutDocument, sourceRevisionRepository.baseline(sourceId),
        )
        assertTrue(DifferenceType.DOCUMENT_REMOVED in removedComparison.differences)
        sourceRevisionRepository.record(
            meetingId, parsed, PublicationStatus.CURRENT, withoutDocument,
            removedComparison, now.plusSeconds(240),
        )
        assertTrue(documentRepository.findPassagesForAnalysis(target.agendaItemId).isEmpty())
    }

    @Test
    fun `workflow lock permits at most one owner and is recoverable`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertTrue(workflowLockRepository.tryAcquire("integration-lock", first))
        assertFalse(workflowLockRepository.tryAcquire("integration-lock", second))
        workflowLockRepository.release("integration-lock", first)
        assertTrue(workflowLockRepository.tryAcquire("integration-lock", second))
        workflowLockRepository.release("integration-lock", second)
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

    private val policyUrl = URI("https://example.invalid/policy.pdf")

    companion object {
        @Container @ServiceConnection @JvmField
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
