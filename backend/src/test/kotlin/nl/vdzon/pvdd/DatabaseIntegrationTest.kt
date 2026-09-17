package nl.vdzon.pvdd

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisCommandStatus
import nl.vdzon.pvdd.analysis.AnalysisRepository
import nl.vdzon.pvdd.analysis.AnalysisRun
import nl.vdzon.pvdd.analysis.AnalysisRunType
import nl.vdzon.pvdd.analysis.AnalysisStatus
import nl.vdzon.pvdd.analysis.AnalysisSource
import nl.vdzon.pvdd.analysis.CitationSourceType
import nl.vdzon.pvdd.analysis.PreparedAnalysisRun
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.documents.DocumentIngestionSummary
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.documents.ExtractedSection
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.documents.SourceDocument
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.api.DashboardController
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaItem
import nl.vdzon.pvdd.meetings.DiscoveredMeeting
import nl.vdzon.pvdd.meetings.DiscoveryOutcome
import nl.vdzon.pvdd.meetings.ImportStatus
import nl.vdzon.pvdd.meetings.Meeting
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MeetingCheckWorkflow
import nl.vdzon.pvdd.meetings.MeetingDiscoveryGateway
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.MeetingStatus
import nl.vdzon.pvdd.meetings.MutationGuard
import nl.vdzon.pvdd.meetings.ParsedMeetingAgenda
import nl.vdzon.pvdd.meetings.AgendaParser
import nl.vdzon.pvdd.meetings.AgendaRevisionComparator
import nl.vdzon.pvdd.meetings.DifferenceType
import nl.vdzon.pvdd.meetings.PublicationStatus
import nl.vdzon.pvdd.meetings.RevisionDocument
import nl.vdzon.pvdd.meetings.SourceRevisionRepository
import nl.vdzon.pvdd.meetings.SourceState
import nl.vdzon.pvdd.meetings.WorkflowLockRepository
import nl.vdzon.pvdd.meetings.api.MeetingCheckController
import nl.vdzon.pvdd.persistence.ApplicationMetadataRepository
import nl.vdzon.pvdd.policy.PolicyChunk
import nl.vdzon.pvdd.policy.CrawledPolicySource
import nl.vdzon.pvdd.policy.PolicyCrawlResult
import nl.vdzon.pvdd.policy.PolicySourceRepository
import nl.vdzon.pvdd.policy.PolicySyncRepository
import nl.vdzon.pvdd.policy.PolicySyncTrigger
import nl.vdzon.pvdd.policy.PolicyTheme
import nl.vdzon.pvdd.policy.PolicyWebSourceType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.module.kotlin.jacksonObjectMapper

// Deze klasse draait tegen een echte PostgreSQL. Met Docker start Testcontainers die zelf, zoals in
// CI. Zonder Docker kan een lege wegwerpdatabase worden meegegeven via PVDD_TEST_DATABASE_URL; dan
// wordt geen container gestart. Is geen van beide er, dan wordt de klasse overgeslagen in plaats van
// het hele vangnet te laten falen. De suite migreert en schrijft, en verwacht een lege database:
// hergebruik van een al gevulde database laat bestaande tests falen (zie requireEmptyDatabase).
@EnabledIf(
    value = "nl.vdzon.pvdd.DatabaseIntegrationTest#databaseAvailable",
    disabledReason = "Geen Docker en geen PVDD_TEST_DATABASE_URL, dus geen PostgreSQL om tegen te draaien",
)
@SpringBootTest
class DatabaseIntegrationTest(
    @param:Autowired private val metadataRepository: ApplicationMetadataRepository,
    @param:Autowired private val meetingRepository: MeetingRepository,
    @param:Autowired private val documentRepository: DocumentRepository,
    @param:Autowired private val analysisRepository: AnalysisRepository,
    @param:Autowired private val policySourceRepository: PolicySourceRepository,
    @param:Autowired private val policySyncRepository: PolicySyncRepository,
    @param:Autowired private val workflowLockRepository: WorkflowLockRepository,
    @param:Autowired private val sourceRevisionRepository: SourceRevisionRepository,
    @param:Autowired private val revisionComparator: AgendaRevisionComparator,
    @param:Autowired private val jdbc: JdbcTemplate,
    @param:Autowired private val dashboardRepository: DashboardRepository,
    @param:Autowired private val dashboardController: DashboardController,
    @param:Autowired private val healthEndpoint: HealthEndpoint,
    @param:Autowired private val userSessionService: UserSessionService,
) {
    // Een container is altijd vers, maar een meegegeven database kan al gevuld zijn. Meerdere tests
    // gaan uit van een lege startsituatie; zonder deze controle falen ze met verwarrende fouten die
    // niets met de wijziging te maken hebben. Eenmaal per run is genoeg.
    @BeforeEach
    fun requireEmptyDatabase() {
        if (emptyDatabaseVerified) return
        val filled = listOf("meeting", "policy_sync_run", "policy_web_source")
            .filter { (jdbc.queryForObject("SELECT COUNT(*) FROM $it", Int::class.java) ?: 0) > 0 }
        check(filled.isEmpty()) {
            "Deze suite verwacht een lege database, maar deze tabellen bevatten al rijen: $filled. " +
                "Gebruik een verse database (Docker, of een lege database via PVDD_TEST_DATABASE_URL)."
        }
        emptyDatabaseVerified = true
    }

    @Test
    fun `degraded policy crawl retains the latest known revision`() {
        val now = Instant.parse("2026-09-01T19:00:00Z")
        val url = URI("https://noordholland.partijvoordedieren.nl/onze-idealen/integratietest-${UUID.randomUUID()}")
        val source = CrawledPolicySource(
            url, PolicyWebSourceType.IDEAL, "Bekend standpunt", null, now, "text/html", 100,
            "a".repeat(64), null, null, "Bekende en eerder succesvol opgehaalde beleidstekst.",
        )
        val firstRun = policySyncRepository.createRun(PolicySyncTrigger.MANUAL, "policy-integration-${UUID.randomUUID()}", now)
        policySyncRepository.persistCandidate(firstRun.id, PolicyCrawlResult(listOf(source)))
        policySyncRepository.fail(firstRun.id, "TEST_COMPLETE")

        val retryRun = policySyncRepository.createRun(
            PolicySyncTrigger.MANUAL,
            "policy-integration-retry-${UUID.randomUUID()}",
            now.plusSeconds(1),
        )
        val candidate = policySyncRepository.persistCandidate(retryRun.id, PolicyCrawlResult(emptyList(), setOf(url)))

        assertEquals(listOf(url), candidate.sources.map { it.url })
        assertEquals("a".repeat(64), candidate.sources.single().sha256)
        policySyncRepository.fail(retryRun.id, "TEST_COMPLETE")
    }

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
        val now = Instant.now()
        val meetingId = UUID.randomUUID()
        val meeting = Meeting(
            id = meetingId,
            sourceId = "meeting-functional-test",
            committee = "Commissie Ruimte",
            startsAt = now.plusSeconds(86400),
            endsAt = now.plusSeconds(100800),
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
            analysisGuidance = "Alleen naar B bij aantoonbare politieke meerwaarde.",
        )
        val preparedId = analysisRepository.createPreparedRun(prepared)
        val firstClaim = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(preparedId, firstClaim.run.id)
        analysisRepository.retrySubmit(preparedId, "LOST_RESPONSE")
        val recoveredClaim = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(prepared.run.idempotencyKey, recoveredClaim.run.idempotencyKey)
        assertEquals(prepared.allowedSources, recoveredClaim.allowedSources)
        assertEquals(prepared.analysisGuidance, recoveredClaim.analysisGuidance)
        analysisRepository.markSubmitted(preparedId, "runtime-job-1", AnalysisStatus.RUNNING)
        assertEquals(1, analysisRepository.activeRuns().single { it.run.id == preparedId }.run.runtimeAttemptCount)
        assertTrue(
            analysisRepository.scheduleRuntimeRetry(
                preparedId,
                "ENGINE_FAILED",
                Instant.EPOCH,
                3,
            ),
        )
        val automaticRetry = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(preparedId, automaticRetry.run.id)
        assertEquals(1, automaticRetry.run.runtimeAttemptCount)
        analysisRepository.markSubmitted(preparedId, "runtime-job-2", AnalysisStatus.RUNNING)
        assertEquals(2, analysisRepository.activeRuns().single { it.run.id == preparedId }.run.runtimeAttemptCount)
        analysisRepository.completeWithAdvice(
            automaticRetry,
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
        assertTrue(requireNotNull(dashboardRepository.item(itemId)).item.canRetryAnalysis)
        val manualRetryId = requireNotNull(
            analysisRepository.retryLatestFailedAnalysis(itemId, now.plusSeconds(8)),
        ).runId
        val manualRetry = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(manualRetryId, manualRetry.run.id)
        assertEquals(laterFailed.run.id, manualRetry.run.retryOfRunId)
        assertEquals(0, manualRetry.run.runtimeAttemptCount)
        assertFalse(requireNotNull(dashboardRepository.item(itemId)).item.canRetryAnalysis)
        analysisRepository.updateRuntimeStatus(manualRetryId, AnalysisStatus.FAILED, "ENGINE_FAILED")
        val bulkRetry = analysisRepository.retryAllLatestFailedAnalyses(now.plusSeconds(9)).single()
        assertEquals(itemId, bulkRetry.agendaItemId)
        val claimedBulkRetry = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(bulkRetry.runId, claimedBulkRetry.run.id)
        analysisRepository.updateRuntimeStatus(bulkRetry.runId, AnalysisStatus.FAILED, "ENGINE_FAILED")

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

        val replayedSourceNote = prepared.copy(
            run = prepared.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"3".repeat(64)}-notes-1",
                status = AnalysisStatus.SUCCEEDED,
                createdAt = now.plusSeconds(9),
                updatedAt = now.plusSeconds(9),
                completedAt = now.plusSeconds(9),
            ),
            prompt = "replayed source notes must not change advice actuality",
            runType = AnalysisRunType.SOURCE_NOTES,
            phaseIndex = 1,
            parentRunId = preparedId,
        )
        analysisRepository.createPreparedRun(replayedSourceNote)
        assertEquals("CURRENT", jdbc.queryForObject(
            "SELECT actuality FROM agenda_item_advice WHERE analysis_run_id = ?",
            String::class.java,
            preparedId,
        ))

        val phasedFinal = prepared.copy(
            run = prepared.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"7".repeat(64)}",
                createdAt = now.plusSeconds(10),
                updatedAt = now.plusSeconds(10),
            ),
            prompt = null,
        )
        val noteRun = prepared.copy(
            run = prepared.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"8".repeat(64)}-notes-1",
                createdAt = now.plusSeconds(10),
                updatedAt = now.plusSeconds(10),
            ),
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
            now.plusSeconds(11),
        )
        val readyFinal = analysisRepository.readySynthesisRuns().single { it.run.id == phasedFinal.run.id }
        assertEquals(1, analysisRepository.sourceNoteResults(readyFinal.run.id).size)
        analysisRepository.activateSynthesis(readyFinal.run.id, "restart-safe synthesis prompt")
        val claimedFinal = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(phasedFinal.run.id, claimedFinal.run.id)
        assertEquals(AnalysisRunType.FINAL_ADVICE, claimedFinal.runType)
        analysisRepository.markSubmitted(claimedFinal.run.id, "runtime-final-1", AnalysisStatus.RUNNING)
        analysisRepository.updateRuntimeStatus(claimedFinal.run.id, AnalysisStatus.FAILED, "ENGINE_FAILED")

        val phasedRetryId = requireNotNull(
            analysisRepository.retryLatestFailedAnalysis(itemId, now.plusSeconds(12)),
        ).runId
        assertEquals(1, analysisRepository.sourceNoteResults(phasedRetryId).size)
        val claimedPhasedRetry = requireNotNull(analysisRepository.claimPendingRun())
        assertEquals(phasedRetryId, claimedPhasedRetry.run.id)
        assertEquals(AnalysisRunType.FINAL_ADVICE, claimedPhasedRetry.runType)
        assertEquals("restart-safe synthesis prompt", claimedPhasedRetry.prompt)
        analysisRepository.updateRuntimeStatus(phasedRetryId, AnalysisStatus.FAILED, "ENGINE_FAILED")

        val superseding = prepared.copy(
            run = prepared.run.copy(
                id = UUID.randomUUID(),
                idempotencyKey = "pvdd-${"4".repeat(64)}",
                createdAt = now.plusSeconds(13),
                updatedAt = now.plusSeconds(13),
            ),
            prompt = "newest analysis makes older failures inapplicable",
        )
        analysisRepository.createPreparedRun(superseding)
        assertNull(analysisRepository.retryLatestFailedAnalysis(itemId, now.plusSeconds(14)))
        assertFalse(requireNotNull(dashboardRepository.item(itemId)).item.canRetryAnalysis)
        meetingRepository.upsert(meeting.copy(startsAt = now.minusSeconds(1)))
        assertFalse(
            analysisRepository.scheduleRuntimeRetry(
                superseding.run.id,
                "ENGINE_FAILED",
                now.plusSeconds(60),
                3,
            ),
        )
        assertTrue(analysisRepository.cancelInapplicablePendingRuns() >= 1)
        assertEquals(AnalysisStatus.CANCELLED, analysisRepository.runControl(superseding.run.id)?.status)
        assertTrue(analysisRepository.retryAllLatestFailedAnalyses(now.plusSeconds(15)).isEmpty())
        assertFalse(requireNotNull(dashboardRepository.item(itemId)).item.canRetryAnalysis)

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
        assertTrue(dashboardItems.filter { it.substantive }.all { it.documentStatus == "DOCUMENTS_UNREADABLE" })
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
        assertEquals("DOCUMENTS_READY", requireNotNull(dashboardRepository.item(target.agendaItemId)).item.documentStatus)
        assertEquals(1, dashboardRepository.overview().progress.total)

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
        assertEquals("NO_DOCUMENTS", requireNotNull(dashboardRepository.item(target.agendaItemId)).item.documentStatus)
        assertEquals(0, dashboardRepository.overview().progress.total)
    }

    @Test
    fun `a past meeting refuses analysis while reading it stays free of side effects`() {
        val startedAt = Instant.now().minusSeconds(7200)
        val meetingId = meetingRepository.upsert(pastMeeting(startedAt))

        try {
            val itemId = meetingRepository.upsert(pastAgendaItem(meetingId))
            val succeeded = succeededAdvice(meetingId, itemId, startedAt)
            val runsBefore = countRuns(meetingId)
            val adviceBefore = adviceSnapshot(itemId)
            assertEquals(1, runsBefore)

            val refusal = assertFailsWith<ResponseStatusException> {
                dashboardController.requestAnalysis(meetingId, adviser, "analyse-past-$meetingId")
            }
            assertEquals(HttpStatus.CONFLICT, refusal.statusCode)
            assertEquals("meeting_in_past", refusal.reason)
            assertEquals(0, countQueued(meetingId))

            assertEquals(itemId, dashboardController.items(meetingId).single().id)
            assertEquals(succeeded, dashboardController.item(itemId).item.lastAnalysisRun?.id)

            assertEquals(runsBefore, countRuns(meetingId))
            assertEquals(adviceBefore, adviceSnapshot(itemId))
            assertEquals(0, countQueued(meetingId))
            assertEquals(startedAt.toEpochMilli(), requireNotNull(meetingRepository.findMeeting(meetingId)).startsAt.toEpochMilli())
        } finally {
            // Laat niets achter, zodat andere tests en een herhaalde run geen last hebben van deze
            // synthetische vergadering. De wachtrij hoort hier leeg te zijn, maar wordt toch geruimd:
            // regresseert de weigering ooit, dan moet de assertie falen en niet de opruiming.
            removeSyntheticMeeting(meetingId)
        }
    }

    @Test
    fun `a future meeting is still queued and an unknown meeting is still not found`() {
        val meetingId = meetingRepository.upsert(
            pastMeeting(Instant.now().plusSeconds(86400)).copy(sourceId = "meeting-future-${UUID.randomUUID()}"),
        )
        try {
            val accepted = dashboardController.requestAnalysis(meetingId, adviser, "analyse-future-$meetingId")

            assertEquals(AnalysisCommandStatus.QUEUED, accepted.status)
            assertEquals(1, countQueued(meetingId))

            val unknownId = UUID.randomUUID()
            assertEquals(
                HttpStatus.NOT_FOUND,
                assertFailsWith<ResponseStatusException> {
                    dashboardController.requestAnalysis(unknownId, adviser, "analyse-unknown-$unknownId")
                }.statusCode,
            )
        } finally {
            // Other tests read the single upcoming meeting, so this synthetic future meeting must not linger.
            removeSyntheticMeeting(meetingId)
        }
    }

    @Test
    fun `check now keeps importing while the last known meeting already took place`() {
        val startedAt = Instant.now().minusSeconds(7200)
        val pastId = meetingRepository.upsert(pastMeeting(startedAt))
        val sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-check-now-${UUID.randomUUID()}")
        val discovered = AgendaParser().parse(
            requireNotNull(javaClass.getResource("/fixtures/meetings/agenda-full.html")).readText(),
            sourceUrl,
        ).copy(startsAt = Instant.now().plusSeconds(86400), endsAt = Instant.now().plusSeconds(90000))
        val previousLastSuccessful = meetingRepository.lastSuccessfulSourceId()
        var importedId: UUID? = null
        try {
            // De voorbije vergadering is ook de laatst bekende, zodat de database in de toestand uit
            // het acceptatiecriterium staat. Let op: de controleroute leest
            // `application_metadata.last-successful-meeting-source-id` niet, dus deze regel stuurt het
            // geteste pad niet. Dat de discovery bij een voorbije laatst bekende vergadering vooruit
            // blijft kijken, ligt vast in MeetingDiscoveryServiceTest; hier gaat het om wat de route
            // tegen de echte database doet en laat.
            meetingRepository.markSuccessful(pastId)
            assertEquals(requireNotNull(meetingRepository.findMeeting(pastId)).sourceId, meetingRepository.lastSuccessfulSourceId())

            val response = checkNowController(discovered).checkNow(adviser, "check-now-$pastId")

            assertEquals(HttpStatus.OK, response.statusCode)
            val result = requireNotNull(response.body)
            assertEquals(MeetingCheckStatus.IMPORTED, result.status)
            assertEquals(discovered.sourceId, result.meetingSourceId)
            importedId = jdbc.queryForList("SELECT id FROM meeting WHERE source_id = ?", UUID::class.java, discovered.sourceId).single()
            assertTrue(meetingRepository.findAgendaItems(importedId).isNotEmpty())

            // De voorbije vergadering blijft ongemoeid: de controle op nieuwe agenda start er geen werk voor.
            assertEquals(0, countQueued(pastId))
            assertEquals(0, countRuns(pastId))
            assertEquals(startedAt.toEpochMilli(), requireNotNull(meetingRepository.findMeeting(pastId)).startsAt.toEpochMilli())
        } finally {
            importedId?.let(::removeSyntheticMeeting)
            removeSyntheticMeeting(pastId)
            restoreLastSuccessfulSourceId(previousLastSuccessful)
        }
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

    private val adviser = "robbertvdzon@gmail.com"

    private fun pastMeeting(startsAt: Instant) = Meeting(
        id = UUID.randomUUID(),
        sourceId = "meeting-past-${UUID.randomUUID()}",
        committee = "Commissie Ruimte",
        startsAt = startsAt,
        endsAt = startsAt.plusSeconds(3600),
        location = "Statenzaal",
        title = "Synthetische vergadering",
        sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-past"),
        sourceHash = "a".repeat(64),
        status = MeetingStatus.COMPLETE,
        checkedAt = startsAt.minusSeconds(3600),
        importedAt = startsAt.minusSeconds(3600),
    )

    private fun pastAgendaItem(meetingId: UUID) = AgendaItem(
        id = UUID.randomUUID(),
        meetingId = meetingId,
        sourceId = "item-past",
        parentSourceId = "section-a",
        sequence = 1,
        displayNumber = "1.a",
        category = AgendaCategory.A,
        title = "Natuurinclusieve woningen",
        explanation = "Synthetische toelichting",
        treatmentProposal = "Bespreken",
        sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-past"),
        sourceHash = "b".repeat(64),
        substantive = true,
        importStatus = ImportStatus.COMPLETE,
    )

    private fun succeededAdvice(meetingId: UUID, itemId: UUID, completedAt: Instant): UUID {
        val prepared = PreparedAnalysisRun(
            run = AnalysisRun(
                id = UUID.randomUUID(),
                agendaItemId = itemId,
                sourceFingerprint = "c".repeat(64),
                promptVersion = "advice-v1",
                selectionVersion = "policy-v1",
                idempotencyKey = "pvdd-past-${UUID.randomUUID()}",
                runtimeJobId = null,
                status = AnalysisStatus.PENDING,
                errorCode = null,
                createdAt = completedAt,
                updatedAt = completedAt,
                completedAt = null,
            ),
            meetingId = meetingId,
            category = "A",
            agendaItemSourceId = "item-past",
            prompt = "synthetisch advies over een voorbije vergadering",
            responseSchema = jacksonObjectMapper().readTree("""{"type":"object"}"""),
            allowedSources = emptyList(),
        )
        val runId = analysisRepository.createPreparedRun(prepared)
        analysisRepository.completeWithAdvice(
            prepared,
            jacksonObjectMapper().readTree("""{"displayTitle":"Advies","shortConclusion":"Steunen","content":"# Advies"}"""),
            jacksonObjectMapper().createArrayNode(),
            "MOCKED",
            "mock-model",
            completedAt,
        )
        return runId
    }

    // Een check-now met een synthetische bron: de discovery zelf blijft ongemoeid, alleen de gateway
    // is vervangen zodat de controleroute zonder externe bron tegen de echte database kan draaien.
    private fun checkNowController(agenda: ParsedMeetingAgenda) = MeetingCheckController(
        MeetingCheckWorkflow(
            StubDiscovery(agenda),
            meetingRepository,
            DocumentIngestor { _, _ -> DocumentIngestionSummary(emptyList(), true) },
            workflowLockRepository,
            ApplicationEventPublisher { },
            Clock.systemUTC(),
            revisionComparator,
            sourceRevisionRepository,
        ),
        MutationGuard(Clock.systemUTC()),
    )

    private class StubDiscovery(private val agenda: ParsedMeetingAgenda) : MeetingDiscoveryGateway {
        override fun discover(now: Instant): DiscoveryOutcome =
            DiscoveryOutcome.Found(DiscoveredMeeting(agenda.sourceId, agenda.startsAt, agenda.sourceUrl))

        override fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean): ParsedMeetingAgenda = agenda
    }

    // Synthetische vergaderingen mogen niets achterlaten. De volgorde volgt de foreign keys; nergens
    // in het schema staat ON DELETE CASCADE, dus revisies, wachtrij, advies en runs gaan voor de
    // agendapunten en de vergadering zelf. Tabellen die in het groene pad leeg horen te zijn worden
    // toch geruimd, zodat bij een regressie de assertie faalt en niet de opruiming.
    private fun removeSyntheticMeeting(meetingId: UUID) {
        jdbc.update(
            """
            DELETE FROM document_revision WHERE agenda_item_revision_id IN (
                SELECT air.id FROM agenda_item_revision air
                JOIN meeting_revision mr ON mr.id = air.meeting_revision_id
                WHERE mr.meeting_id = ?
            )
            """.trimIndent(),
            meetingId,
        )
        jdbc.update(
            "DELETE FROM agenda_item_revision WHERE meeting_revision_id IN (SELECT id FROM meeting_revision WHERE meeting_id = ?)",
            meetingId,
        )
        jdbc.update("DELETE FROM meeting_revision WHERE meeting_id = ?", meetingId)
        jdbc.update("DELETE FROM source_check WHERE meeting_id = ?", meetingId)
        jdbc.update("DELETE FROM analysis_meeting_queue WHERE meeting_id = ?", meetingId)
        jdbc.update("DELETE FROM agenda_item_advice WHERE agenda_item_id IN (SELECT id FROM agenda_item WHERE meeting_id = ?)", meetingId)
        jdbc.update("DELETE FROM analysis_run WHERE meeting_id = ?", meetingId)
        jdbc.update("DELETE FROM source_document WHERE agenda_item_id IN (SELECT id FROM agenda_item WHERE meeting_id = ?)", meetingId)
        jdbc.update("DELETE FROM agenda_item WHERE meeting_id = ?", meetingId)
        jdbc.update("DELETE FROM meeting WHERE id = ?", meetingId)
    }

    // De laatst bekende vergadering is gedeelde toestand; zet die terug zoals andere tests hem vonden.
    private fun restoreLastSuccessfulSourceId(sourceId: String?) {
        if (sourceId == null) {
            jdbc.update("DELETE FROM application_metadata WHERE metadata_key = 'last-successful-meeting-source-id'")
        } else {
            jdbc.update(
                "UPDATE application_metadata SET metadata_value = ? WHERE metadata_key = 'last-successful-meeting-source-id'",
                sourceId,
            )
        }
    }

    private fun countRuns(meetingId: UUID): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM analysis_run WHERE meeting_id = ?",
        Int::class.java,
        meetingId,
    ) ?: 0

    private fun countQueued(meetingId: UUID): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM analysis_meeting_queue WHERE meeting_id = ?",
        Int::class.java,
        meetingId,
    ) ?: 0

    private fun adviceSnapshot(itemId: UUID): List<String> = jdbc.query(
        "SELECT id::text || '|' || actuality || '|' || advice::text snapshot FROM agenda_item_advice WHERE agenda_item_id = ? ORDER BY id",
        { rs, _ -> rs.getString("snapshot") },
        itemId,
    )

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
        // Een meegegeven wegwerpdatabase wint van Docker, zodat deze tests ook in een Dockerloze
        // omgeving tegen een lokaal gestarte PostgreSQL kunnen draaien. De sleutel is bewust
        // testeigen: de runtimesleutel PVDD_DATABASE_URL wijst in acceptatie en productie naar een
        // echte database en mag deze schrijvende en migrerende suite daar nooit heen sturen.
        private val testDatabaseUrl: String? = System.getenv("PVDD_TEST_DATABASE_URL")

        private val postgres: PostgreSQLContainer? by lazy {
            if (testDatabaseUrl != null) null else PostgreSQLContainer("postgres:16-alpine").apply { start() }
        }

        private var emptyDatabaseVerified = false

        @JvmStatic
        fun databaseAvailable(): Boolean =
            testDatabaseUrl != null || DockerClientFactory.instance().isDockerAvailable

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            val container = postgres
            val url = container?.jdbcUrl ?: testDatabaseUrl ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { container?.username ?: (System.getenv("PVDD_TEST_DATABASE_USER") ?: "pvdd") }
            registry.add("spring.datasource.password") { container?.password ?: (System.getenv("PVDD_TEST_DATABASE_PASSWORD") ?: "") }
        }
    }
}
