package nl.vdzon.pvdd

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
import nl.vdzon.pvdd.dashboard.AdviceVersionDto
import nl.vdzon.pvdd.dashboard.AdviceVersionsDto
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.ProgressDto
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
    fun `reading one meeting returns its header progress and past marker without touching stored work`() {
        // Ruime marges: `past` volgt `starts_at < CURRENT_TIMESTAMP` op databasetijd, dus twee uur
        // terug en een dag vooruit leggen het gedrag vast zonder op de microseconde te leunen.
        val past = seedMeetingWithAdvice(Instant.now().minusSeconds(7200))
        var future: SeededMeeting? = null
        try {
            // Zolang de voorbije vergadering de enige is, levert /api/meetings/next haar terug en
            // moet ook daar `past` waar zijn: de markering hangt aan de databasetijd, niet aan een client.
            val nextWhenOnlyPast = requireNotNull(dashboardController.next().meeting)
            assertEquals(past.meetingId, nextWhenOnlyPast.id)
            assertTrue(nextWhenOnlyPast.past)

            future = seedMeetingWithAdvice(Instant.now().plusSeconds(86400))
            val nextWhenFutureExists = requireNotNull(dashboardController.next().meeting)
            assertEquals(future.meetingId, nextWhenFutureExists.id)
            assertFalse(nextWhenFutureExists.past)

            val runsBefore = countAllRuns()
            val adviceBefore = adviceSnapshot(past.agendaItemId)
            val queuedBefore = countQueued(past.meetingId)

            val overview = requireNotNull(dashboardController.meeting(past.meetingId))
            val meeting = requireNotNull(overview.meeting)
            assertEquals(past.meetingId, meeting.id)
            assertEquals("Synthetische vergadering", meeting.title)
            assertEquals("Commissie Ruimte", meeting.committee)
            assertEquals("Statenzaal", meeting.location)
            assertEquals(URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-past"), meeting.sourceUrl)
            assertEquals(MeetingStatus.COMPLETE.name, meeting.status)
            assertEquals(meeting.status, overview.status)
            assertEquals(0, meeting.revisionNumber)
            assertTrue(meeting.past)
            assertEquals(ProgressDto(1, 1, 0), overview.progress)
            assertNotNull(overview.lastCheckedAt)

            assertFalse(requireNotNull(dashboardController.meeting(future.meetingId).meeting).past)

            val unknownId = UUID.randomUUID()
            assertEquals(
                HttpStatus.NOT_FOUND,
                assertFailsWith<ResponseStatusException> { dashboardController.meeting(unknownId) }.statusCode,
            )

            // Terugkijken is strikt lezen: de agendapunten en het itemdetail erbij mogen geen enkele
            // rij in analysis_run of de wachtrij opleveren en geen bestaand advies wijzigen.
            assertEquals(past.agendaItemId, dashboardController.items(past.meetingId).single().id)
            assertEquals(past.analysisRunId, dashboardController.item(past.agendaItemId).item.lastAnalysisRun?.id)

            assertEquals(runsBefore, countAllRuns())
            assertEquals(adviceBefore, adviceSnapshot(past.agendaItemId))
            assertEquals(queuedBefore, countQueued(past.meetingId))
            assertEquals(0, countQueued(past.meetingId))
        } finally {
            future?.let { removeSyntheticMeeting(it.meetingId) }
            removeSyntheticMeeting(past.meetingId)
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
    fun `an archive without past meetings stays empty instead of showing a blank list`() {
        val hidden = hideExistingPastMeetings()
        try {
            val page = dashboardController.meetings("past", null, null)

            assertEquals(emptyList(), page.items)
            assertEquals(0, page.total)
            assertNull(page.nextCursor)
        } finally {
            restoreStartTimes(hidden)
        }
    }

    @Test
    fun `the archive lists only past meetings with the newest first`() {
        val now = Instant.now()
        val hidden = hideExistingPastMeetings()
        val seeded = mutableListOf<SeededArchiveMeeting>()
        try {
            val recent = seedArchiveMeeting(now.minusSeconds(3600), title = "Meest recente").also(seeded::add)
            val older = seedArchiveMeeting(now.minusSeconds(7200), title = "Ouder").also(seeded::add)
            // Gelijk begintijdstip: de id beslist, aflopend.
            val tieA = seedArchiveMeeting(now.minusSeconds(10800), title = "Gelijk tijdstip A").also(seeded::add)
            val tieB = seedArchiveMeeting(now.minusSeconds(10800), title = "Gelijk tijdstip B").also(seeded::add)
            val future = seedArchiveMeeting(now.plusSeconds(86400), title = "Toekomst").also(seeded::add)

            // Grensgeval: een vergadering die precies nu begint telt niet als voorbij. De update en
            // de vergelijking staan in één statement, zodat CURRENT_TIMESTAMP daarbinnen vaststaat.
            val boundary = seedArchiveMeeting(now.minusSeconds(60), title = "Grensgeval").also(seeded::add)
            val boundaryCountsAsPast = jdbc.queryForObject(
                """
                WITH moved AS (
                    UPDATE meeting SET starts_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING starts_at
                )
                SELECT moved.starts_at < CURRENT_TIMESTAMP FROM moved
                """.trimIndent(),
                Boolean::class.java,
                boundary.meetingId,
            )
            assertEquals(false, boundaryCountsAsPast)
            // Daarna vooruit gezet, zodat hij net als elke toekomstige vergadering buiten de lijst valt.
            jdbc.update(
                "UPDATE meeting SET starts_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(86400)),
                boundary.meetingId,
            )

            val page = dashboardController.meetings("past", null, null)

            assertEquals(4, page.total)
            assertNull(page.nextCursor)
            // Bij gelijk begintijdstip beslist de id aflopend; PostgreSQL vergelijkt uuid op bytes,
            // wat overeenkomt met de hexadecimale tekstvolgorde.
            val ties = listOf(tieA, tieB).sortedByDescending { it.meetingId.toString() }
            assertEquals(
                listOf(recent.meetingId, older.meetingId, ties[0].meetingId, ties[1].meetingId),
                page.items.map { it.id },
            )
            assertFalse(page.items.any { it.id == future.meetingId || it.id == boundary.meetingId })
            assertEquals(listOf("Meest recente", "Ouder"), page.items.take(2).map { it.title })
            assertEquals(recent.startsAt.toEpochMilli(), page.items.first().startsAt.toEpochMilli())
            assertEquals("Statenzaal", page.items.first().location)
        } finally {
            seeded.forEach { removeSyntheticMeeting(it.meetingId) }
            restoreStartTimes(hidden)
        }
    }

    @Test
    fun `archive counts use exactly the same filter as the progress count`() {
        val startsAt = Instant.now().minusSeconds(7200)
        val seeded = seedArchiveMeeting(
            startsAt,
            title = "Vergadering met randgevallen",
            location = "Provinciehuis, Haarlem",
            succeeded = 2,
            failed = 1,
            withoutAdvice = 1,
            withNoise = true,
        )
        try {
            val item = dashboardController.meetings("past", null, null).items.single { it.id == seeded.meetingId }
            val progress = dashboardController.meeting(seeded.meetingId).progress

            assertEquals(4, seeded.substantiveItems)
            assertEquals(seeded.substantiveItems, item.substantiveItemCount)
            assertEquals(seeded.completedAdvice, item.completedAdviceCount)
            // Exact hetzelfde filter: de telling per vergadering en de voortgangstelling van het
            // agendascherm leveren voor dezelfde vergadering dezelfde aantallen.
            assertEquals(progress.total, item.substantiveItemCount)
            assertEquals(progress.complete, item.completedAdviceCount)
            assertEquals(ProgressDto(4, 2, 1), progress)

            assertEquals("Vergadering met randgevallen", item.title)
            assertEquals("Provinciehuis, Haarlem", item.location)
            assertEquals(startsAt.toEpochMilli(), item.startsAt.toEpochMilli())
        } finally {
            removeSyntheticMeeting(seeded.meetingId)
        }
    }

    @Test
    fun `paging through the archive returns every meeting exactly once`() {
        val now = Instant.now()
        val hidden = hideExistingPastMeetings()
        // Vijfentwintig vergaderingen: bij een limiet van vijf bevat de laatste pagina precies de
        // limiet en mag er tóch geen cursor meer volgen.
        val seeded = (1..25).map { index ->
            seedArchiveMeeting(now.minusSeconds(3600L * index), title = "Archiefvergadering $index")
        }
        try {
            val expected = seeded.map { it.meetingId }

            // Zonder limiet: twintig items en een cursor.
            val first = dashboardController.meetings("past", null, null)
            assertEquals(20, first.items.size)
            assertEquals(25, first.total)
            assertNotNull(first.nextCursor)
            assertEquals(expected.take(20), first.items.map { it.id })

            val seen = mutableListOf<UUID>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = dashboardController.meetings("past", "5", cursor)
                pages += 1
                assertEquals(25, page.total)
                assertEquals(5, page.items.size)
                seen += page.items.map { it.id }
                cursor = page.nextCursor
            } while (cursor != null && pages < 10)

            assertEquals(5, pages)
            assertEquals(expected, seen)
            assertEquals(seen.size, seen.toSet().size)

            // Een ongeldige aanvraag levert ook tegen de echte database HTTP 400 met de foutcode.
            for (query in listOf(null to null, "future" to null, "past" to "0", "past" to "51", "past" to "abc")) {
                val refusal = assertFailsWith<ResponseStatusException>("$query") {
                    dashboardController.meetings(query.first, query.second, null)
                }
                assertEquals(HttpStatus.BAD_REQUEST, refusal.statusCode, "$query")
                assertEquals("invalid_meeting_query", refusal.reason, "$query")
            }
            assertEquals(
                "invalid_meeting_query",
                assertFailsWith<ResponseStatusException> { dashboardController.meetings("past", null, "!!!") }.reason,
            )
        } finally {
            seeded.forEach { removeSyntheticMeeting(it.meetingId) }
            restoreStartTimes(hidden)
        }
    }

    @Test
    fun `advice versions list the saved versions newest first and reject an unknown agenda item`() {
        val older = Instant.parse("2026-09-03T09:12:00Z")
        val newer = Instant.parse("2026-09-07T05:41:00Z")
        val seeded = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        val withoutAdvice = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        try {
            val first = seedAdviceVersion(
                seeded.meetingId, seeded.agendaItemId, older,
                guidance = "Weeg de natuurnormen zwaarder",
            )
            // Een lege guidance is bewust anders dan geen kolomwaarde: de kolom is NOT NULL met ''.
            val second = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, newer, guidance = "   ")

            val versions = dashboardController.adviceVersions(seeded.agendaItemId).versions
            assertEquals(listOf(second.adviceId, first.adviceId), versions.map { it.adviceId })
            assertEquals(listOf(second.runId, first.runId), versions.map { it.analysisRunId })
            assertEquals(listOf(newer, older), versions.map { it.createdAt })
            assertEquals(listOf("CURRENT", "STALE"), versions.map { it.actuality })
            // Alleen de eerste rij in de ordening is het laatste advies.
            assertEquals(listOf(true, false), versions.map { it.latest })

            val latest = versions.first()
            assertEquals("Advies", latest.displayTitle)
            assertEquals("Steunen", latest.shortConclusion)
            assertEquals("# Advies", latest.advice.path("content").asText())
            assertEquals("Advies", latest.advice.path("displayTitle").asText())
            assertEquals("MOCKED", latest.provider)
            assertEquals("mock-model", latest.model)
            assertEquals("advice-v1", latest.promptVersion)
            // Leeg of alleen witruimte wordt null; de bewaarde tekst gaat ongewijzigd mee.
            assertNull(latest.analysisGuidance)
            assertEquals("Weeg de natuurnormen zwaarder", versions[1].analysisGuidance)

            // Een bestaand agendapunt zonder geslaagde adviesrun levert een lege lijst, geen 404.
            assertEquals(emptyList(), dashboardController.adviceVersions(withoutAdvice.agendaItemId).versions)

            assertEquals(
                HttpStatus.NOT_FOUND,
                assertFailsWith<ResponseStatusException> {
                    dashboardController.adviceVersions(UUID.randomUUID())
                }.statusCode,
            )
        } finally {
            removeSyntheticMeeting(withoutAdvice.meetingId)
            removeSyntheticMeeting(seeded.meetingId)
        }
    }

    @Test
    fun `advice versions leave out runs that did not succeed`() {
        val seeded = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        try {
            val succeeded = seedAdviceVersion(
                seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-01T09:00:00Z"),
            )
            // Beide houden hun adviesrij, maar de run is niet geslaagd: ze horen niet in de lijst.
            seedAdviceVersion(
                seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-04T09:00:00Z"),
                status = AnalysisStatus.FAILED,
            )
            seedAdviceVersion(
                seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-05T09:00:00Z"),
                status = AnalysisStatus.CANCELLED,
            )

            val versions = dashboardController.adviceVersions(seeded.agendaItemId).versions
            assertEquals(listOf(succeeded.adviceId), versions.map { it.adviceId })
            assertTrue(versions.single().latest)
        } finally {
            removeSyntheticMeeting(seeded.meetingId)
        }
    }

    @Test
    fun `advice versions derive the refresh reason exactly like the ai runs view`() {
        val seeded = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        try {
            // Eerste analyse, daarna een heranalyse zonder retry, daarna een handmatige herstart.
            val first = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-01T09:00:00Z"))
            val reanalysis = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-03T09:00:00Z"))
            val retry = seedAdviceVersion(
                seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-07T09:00:00Z"),
                retryOfRunId = reanalysis.runId,
            )

            val reasons = dashboardController.adviceVersions(seeded.agendaItemId)
                .versions.associate { it.analysisRunId to it.refreshReason }
            assertEquals("MANUAL_RETRY", reasons[retry.runId])
            assertEquals("CONTEXT_CHANGED", reasons[reanalysis.runId])
            assertEquals("FIRST_ANALYSIS", reasons[first.runId])

            // Dezelfde afleiding als de AI-runsweergave: elke soortcode daar hoort bij dezelfde run.
            assertEquals("AGENDA_RETRY", dashboardController.aiRun(retry.runId).run.type)
            assertEquals("AGENDA_REANALYSIS", dashboardController.aiRun(reanalysis.runId).run.type)
            assertEquals("AGENDA_ADVICE", dashboardController.aiRun(first.runId).run.type)
        } finally {
            removeSyntheticMeeting(seeded.meetingId)
        }
    }

    @Test
    fun `reading advice versions carries no source list and changes nothing`() {
        val seeded = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        try {
            seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-01T09:00:00Z"))
            seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-07T09:00:00Z"))

            val runsBefore = countAllRuns()
            val adviceBefore = countAllAdvice()
            val snapshotBefore = adviceSnapshot(seeded.agendaItemId)

            val response = dashboardController.adviceVersions(seeded.agendaItemId)

            // Geen bron-, citatie- of revisieveld, op geen enkel niveau van het antwoord.
            val forbidden = listOf("citation", "citatie", "source", "bron", "revision")
            val fieldNames = AdviceVersionDto::class.java.declaredFields.map { it.name } +
                AdviceVersionsDto::class.java.declaredFields.map { it.name }
            fieldNames.forEach { field ->
                assertTrue(
                    forbidden.none { field.lowercase().contains(it) },
                    "Veld $field verwijst naar bronnen of citaten",
                )
            }
            val serialized = jacksonObjectMapper().writeValueAsString(response).lowercase()
            forbidden.forEach { assertFalse(serialized.contains(it), "Het antwoord bevat '$it'") }

            assertEquals(runsBefore, countAllRuns())
            assertEquals(adviceBefore, countAllAdvice())
            assertEquals(snapshotBefore, adviceSnapshot(seeded.agendaItemId))
        } finally {
            removeSyntheticMeeting(seeded.meetingId)
        }
    }

    @Test
    fun `a withdrawn advice sorts after current and stale even when it is the newest`() {
        val seeded = seedMeetingWithAgendaItem(Instant.now().minusSeconds(7200))
        try {
            val oldest = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-01T09:00:00Z"))
            val middle = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-03T09:00:00Z"))
            val newest = seedAdviceVersion(seeded.meetingId, seeded.agendaItemId, Instant.parse("2026-09-07T09:00:00Z"))
            // De chronologisch nieuwste versie is ingetrokken; de ordening van `item()` zet haar
            // daarmee achteraan, dus zij is niet het 'laatste advies'. De middelste rij neemt de
            // markering CURRENT over, precies zoals de detailweergave haar dan zou tonen.
            jdbc.update("UPDATE agenda_item_advice SET actuality = 'WITHDRAWN' WHERE id = ?", newest.adviceId)
            jdbc.update("UPDATE agenda_item_advice SET actuality = 'CURRENT' WHERE id = ?", middle.adviceId)

            val versions = dashboardController.adviceVersions(seeded.agendaItemId).versions
            assertEquals(listOf(middle.adviceId, oldest.adviceId, newest.adviceId), versions.map { it.adviceId })
            assertEquals(listOf("CURRENT", "STALE", "WITHDRAWN"), versions.map { it.actuality })
            assertEquals(listOf(true, false, false), versions.map { it.latest })
            assertEquals(middle.adviceId, versions.single { it.latest }.adviceId)
        } finally {
            removeSyntheticMeeting(seeded.meetingId)
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

    private data class SeededMeeting(val meetingId: UUID, val agendaItemId: UUID, val analysisRunId: UUID)

    // Testvoorziening voor terugkijkgedrag: schrijft één vergadering weg met agendapunt, een leesbaar
    // document en een afgerond advies. Met een `startsAt` in het verleden of in de toekomst levert
    // dit de twee situaties die de `past`-markering moet onderscheiden. Opruimen doet
    // `removeSyntheticMeeting(meetingId)`.
    private fun seedMeetingWithAdvice(startsAt: Instant): SeededMeeting {
        val seeded = seedMeetingWithAgendaItem(startsAt)
        return SeededMeeting(
            seeded.meetingId,
            seeded.agendaItemId,
            succeededAdvice(seeded.meetingId, seeded.agendaItemId, startsAt),
        )
    }

    private data class SeededAgendaItem(val meetingId: UUID, val agendaItemId: UUID)

    /**
     * Eén vergadering met één inhoudelijk agendapunt en een leesbaar document, nog zónder advies.
     * Zo kan een test zelf bepalen welke runs en adviesrijen er komen; opruimen doet
     * `removeSyntheticMeeting(meetingId)`.
     */
    private fun seedMeetingWithAgendaItem(startsAt: Instant): SeededAgendaItem {
        val unique = UUID.randomUUID()
        val meetingId = meetingRepository.upsert(pastMeeting(startsAt).copy(sourceId = "meeting-seed-$unique"))
        val itemId = meetingRepository.upsert(pastAgendaItem(meetingId))
        // Zonder leesbaar document telt het agendapunt niet mee in de voortgangstelling.
        assertTrue(documentRepository.insertVersion(document(itemId, unique.toString().replace("-", "").repeat(2), startsAt)))
        return SeededAgendaItem(meetingId, itemId)
    }

    private fun preparedAdviceRun(
        meetingId: UUID,
        itemId: UUID,
        createdAt: Instant,
        guidance: String = "",
        retryOfRunId: UUID? = null,
    ) = PreparedAnalysisRun(
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
            createdAt = createdAt,
            updatedAt = createdAt,
            completedAt = null,
            retryOfRunId = retryOfRunId,
        ),
        meetingId = meetingId,
        category = "A",
        agendaItemSourceId = "item-past",
        prompt = "synthetisch advies over een voorbije vergadering",
        responseSchema = jacksonObjectMapper().readTree("""{"type":"object"}"""),
        allowedSources = emptyList(),
        analysisGuidance = guidance,
    )

    private data class SeededAdviceVersion(val runId: UUID, val adviceId: UUID)

    /**
     * Zaaihelper voor adviesversies: één `FINAL_ADVICE`-run met bijbehorende adviesrij.
     *
     * Met [status] anders dan `SUCCEEDED` blijft de adviesrij staan terwijl de run mislukt of
     * geannuleerd is — precies het geval dat de versielijst moet uitsluiten. [guidance] vult
     * `analysis_run.analysis_guidance` (leeg betekent: niets bewaard) en [retryOfRunId] maakt er een
     * handmatig herstarte run van. De `actuality` van alle adviesrijen van dit agendapunt wordt door
     * de productiecode zelf herberekend, dus zaai in chronologische volgorde.
     */
    private fun seedAdviceVersion(
        meetingId: UUID,
        itemId: UUID,
        createdAt: Instant,
        status: AnalysisStatus = AnalysisStatus.SUCCEEDED,
        guidance: String = "",
        retryOfRunId: UUID? = null,
        advice: String = """{"displayTitle":"Advies","shortConclusion":"Steunen","content":"# Advies"}""",
    ): SeededAdviceVersion {
        val prepared = preparedAdviceRun(meetingId, itemId, createdAt, guidance, retryOfRunId)
        val runId = analysisRepository.createPreparedRun(prepared)
        analysisRepository.completeWithAdvice(
            prepared,
            jacksonObjectMapper().readTree(advice),
            jacksonObjectMapper().createArrayNode(),
            "MOCKED",
            "mock-model",
            createdAt,
        )
        if (status != AnalysisStatus.SUCCEEDED) analysisRepository.updateRuntimeStatus(runId, status, "synthetic_$status")
        val adviceId = requireNotNull(
            jdbc.queryForObject("SELECT id FROM agenda_item_advice WHERE analysis_run_id = ?", UUID::class.java, runId),
        )
        return SeededAdviceVersion(runId, adviceId)
    }

    // Een mislukte FINAL_ADVICE-run: het agendapunt telt wel mee als inhoudelijk punt, maar niet
    // als punt met afgerond advies.
    private fun failedAdvice(meetingId: UUID, itemId: UUID, createdAt: Instant): UUID {
        val runId = analysisRepository.createPreparedRun(preparedAdviceRun(meetingId, itemId, createdAt))
        analysisRepository.updateRuntimeStatus(runId, AnalysisStatus.FAILED, "synthetic_failure")
        return runId
    }

    /**
     * Schuift de vergaderingen die andere tests hebben achtergelaten tijdelijk naar de toekomst, zodat
     * de archieflijst deterministisch te toetsen is zonder gegevens te verwijderen. De teruggegeven
     * lijst zet `restoreStartTimes` in het `finally` exact terug.
     */
    private fun hideExistingPastMeetings(): List<Pair<UUID, java.sql.Timestamp>> {
        val existing = jdbc.query(
            "SELECT id, starts_at FROM meeting WHERE starts_at < CURRENT_TIMESTAMP",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getTimestamp("starts_at") },
        )
        val future = java.sql.Timestamp.from(Instant.now().plusSeconds(864000))
        existing.forEach { jdbc.update("UPDATE meeting SET starts_at = ? WHERE id = ?", future, it.first) }
        return existing
    }

    private fun restoreStartTimes(rows: List<Pair<UUID, java.sql.Timestamp>>) {
        rows.forEach { jdbc.update("UPDATE meeting SET starts_at = ? WHERE id = ?", it.second, it.first) }
    }

    private data class SeededArchiveMeeting(
        val meetingId: UUID,
        val startsAt: Instant,
        val substantiveItems: Int,
        val completedAdvice: Int,
    )

    /**
     * Zaaihelper voor het archiefoverzicht: één bewaarde vergadering met [succeeded] afgeronde,
     * [failed] mislukte en [withoutAdvice] nog niet geanalyseerde inhoudelijke agendapunten, elk met
     * een leesbaar document.
     *
     * Met [withNoise] komen daar de randgevallen bij die in geen enkele telling mogen meedoen: een
     * ingetrokken punt, een niet-inhoudelijk punt, een punt buiten de categorieën A/B/C en een punt
     * zonder leesbaar document. Opruimen doet `removeSyntheticMeeting(meetingId)`.
     */
    private fun seedArchiveMeeting(
        startsAt: Instant,
        title: String = "Synthetische vergadering",
        location: String? = "Statenzaal",
        succeeded: Int = 1,
        failed: Int = 0,
        withoutAdvice: Int = 0,
        withNoise: Boolean = false,
    ): SeededArchiveMeeting {
        val unique = UUID.randomUUID()
        val meetingId = meetingRepository.upsert(
            pastMeeting(startsAt).copy(sourceId = "meeting-archive-$unique", title = title, location = location),
        )
        var sequence = 0
        fun agendaItem(
            category: AgendaCategory = AgendaCategory.A,
            substantive: Boolean = true,
            sourceState: SourceState = SourceState.CURRENT,
            readableDocument: Boolean = true,
        ): UUID {
            sequence += 1
            val itemId = meetingRepository.upsert(
                pastAgendaItem(meetingId).copy(
                    id = UUID.randomUUID(),
                    sourceId = "item-archive-$unique-$sequence",
                    sequence = sequence,
                    category = category,
                    substantive = substantive,
                    sourceState = sourceState,
                ),
            )
            if (readableDocument) {
                assertTrue(
                    documentRepository.insertVersion(
                        document(itemId, UUID.randomUUID().toString().replace("-", "").repeat(2), startsAt),
                    ),
                )
            }
            return itemId
        }

        repeat(succeeded) { succeededAdvice(meetingId, agendaItem(), startsAt) }
        repeat(failed) { failedAdvice(meetingId, agendaItem(), startsAt) }
        repeat(withoutAdvice) { agendaItem() }
        if (withNoise) {
            // Deze vier tellen nergens mee: ze vallen op precies dezelfde voorwaarden af als in de
            // bestaande voortgangstelling. Ze krijgen wel een afgerond advies, zodat een te ruime
            // telling meteen zichtbaar zou worden.
            succeededAdvice(meetingId, agendaItem(sourceState = SourceState.WITHDRAWN), startsAt)
            succeededAdvice(meetingId, agendaItem(substantive = false), startsAt)
            succeededAdvice(meetingId, agendaItem(category = AgendaCategory.OTHER), startsAt)
            succeededAdvice(meetingId, agendaItem(readableDocument = false), startsAt)
        }
        return SeededArchiveMeeting(meetingId, startsAt, succeeded + failed + withoutAdvice, succeeded)
    }

    private fun succeededAdvice(meetingId: UUID, itemId: UUID, completedAt: Instant): UUID =
        seedAdviceVersion(meetingId, itemId, completedAt).runId

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

    // Telt de hele tabel, zodat een leesaanroep ook geen rij kan toevoegen voor een andere vergadering.
    private fun countAllRuns(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run", Int::class.java) ?: 0

    private fun countAllAdvice(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM agenda_item_advice", Int::class.java) ?: 0

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
