package nl.vdzon.pvdd.analysis

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import nl.vdzon.pvdd.runtime.AcceptanceRuntimeFixture
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.meetings.Meeting
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.MeetingStatus
import nl.vdzon.pvdd.source.Commissie
import nl.vdzon.pvdd.policy.PolicyImportService
import nl.vdzon.pvdd.policy.PolicySelector
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import nl.vdzon.pvdd.runtime.AgentRuntimeProperties
import nl.vdzon.pvdd.runtime.RuntimeCreateRequest
import nl.vdzon.pvdd.runtime.RuntimeExecution
import nl.vdzon.pvdd.runtime.RuntimeJob
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.module.kotlin.jacksonObjectMapper

class AnalysisModelRoutingTest {
    private val mapper = jacksonObjectMapper()
    private val notesModel = RuntimeExecution("anthropic", "claude-haiku-4-5-20251001", "SUBSCRIPTION")
    private val adviceModel = RuntimeExecution("anthropic", "claude-opus-5", "SUBSCRIPTION")

    private val repository = mock(AnalysisRepository::class.java)
    private val runtime = mock(AgentRuntimeGateway::class.java)
    private val runtimeProperties = AgentRuntimeProperties()
    private val submittedRequests = mutableListOf<RuntimeCreateRequest>()
    private val models = mock(AnalysisModelSettings::class.java).also {
        `when`(it.execution(AnalysisModelTask.SOURCE_NOTES)).thenReturn(notesModel)
        `when`(it.execution(AnalysisModelTask.FINAL_ADVICE)).thenReturn(adviceModel)
    }
    private val meetingRepository = mock(MeetingRepository::class.java)
    private val orchestrator = AnalysisOrchestrator(
        repository,
        meetingRepository,
        mock(DocumentRepository::class.java),
        mock(PolicyImportService::class.java),
        mock(PolicySelector::class.java),
        mock(PolicyPositionCatalogue::class.java),
        mock(AnalysisGuidanceService::class.java),
        mock(PromptBuilder::class.java),
        mock(ContentResultValidator::class.java),
        runtime,
        runtimeProperties,
        models,
        mapper,
        Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `source notes and final advice are submitted with the model chosen for their task`() {
        assertEquals(notesModel, submittedExecution(AnalysisRunType.SOURCE_NOTES))
        assertEquals(adviceModel, submittedExecution(AnalysisRunType.FINAL_ADVICE))
    }

    @Test
    fun `acceptance selects typed fixtures from trusted phase and category with distinct keys for fresh runs`() {
        runtimeProperties.environment = "acceptance"
        runtimeProperties.acceptanceFixturesEnabled = true
        runtimeProperties.mockControlToken = "backend-only-fixture-credential"
        val mockExecution = RuntimeExecution("mock", "mock", "MOCK")
        `when`(models.execution(AnalysisModelTask.SOURCE_NOTES)).thenReturn(mockExecution)
        `when`(models.execution(AnalysisModelTask.FINAL_ADVICE)).thenReturn(mockExecution)
        submittedExecution(AnalysisRunType.SOURCE_NOTES)
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "A")
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "B")
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "C")
        assertEquals(listOf(AcceptanceRuntimeFixture.SOURCE_NOTES, AcceptanceRuntimeFixture.ADVICE_AB,
            AcceptanceRuntimeFixture.ADVICE_AB, AcceptanceRuntimeFixture.ADVICE_C), submittedRequests.map { it.acceptanceFixture })
        assertTrue(submittedRequests.all { it.idempotencyKey.contains("-fixture-") && it.idempotencyKey.length <= 160 })
        val run = prepared(AnalysisRunType.FINAL_ADVICE).run
        assertEquals(runtimeExecutionKey(run, true), runtimeExecutionKey(run.copy(), true))
        assertNotEquals(runtimeExecutionKey(run, true), runtimeExecutionKey(run.copy(id=UUID.randomUUID()), true))
        assertEquals(runtimeExecutionKey(run), runtimeExecutionKey(run.copy(id=UUID.randomUUID())))
    }

    @Test
    fun `acceptance gives a Bestuur meeting the synthetic Bestuur fixtures and Ruimte the existing ones`() {
        runtimeProperties.environment = "acceptance"
        runtimeProperties.acceptanceFixturesEnabled = true
        runtimeProperties.mockControlToken = "backend-only-fixture-credential"
        val mockExecution = RuntimeExecution("mock", "mock", "MOCK")
        `when`(models.execution(AnalysisModelTask.FINAL_ADVICE)).thenReturn(mockExecution)
        val bestuur = UUID.randomUUID()
        val ruimte = UUID.randomUUID()
        `when`(meetingRepository.findMeeting(bestuur)).thenReturn(meeting(bestuur, Commissie.BESTUUR))
        `when`(meetingRepository.findMeeting(ruimte)).thenReturn(meeting(ruimte, Commissie.RUIMTE))

        submittedExecution(AnalysisRunType.FINAL_ADVICE, "A", bestuur)
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "B", bestuur)
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "C", bestuur)
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "A", ruimte)
        submittedExecution(AnalysisRunType.FINAL_ADVICE, "C", ruimte)

        assertEquals(
            listOf(
                AcceptanceRuntimeFixture.BESTUUR_ADVICE_AB, AcceptanceRuntimeFixture.BESTUUR_ADVICE_AB,
                AcceptanceRuntimeFixture.BESTUUR_ADVICE_C, AcceptanceRuntimeFixture.ADVICE_AB, AcceptanceRuntimeFixture.ADVICE_C,
            ),
            submittedRequests.map { it.acceptanceFixture },
        )
    }

    private fun meeting(id: UUID, commissie: Commissie) = Meeting(
        id = id, sourceId = "m-$id", committee = commissie.defaultName, startsAt = Instant.EPOCH, endsAt = null, location = null,
        title = "Synthetisch", sourceUrl = URI("http://localhost/m"), sourceHash = "a".repeat(64), status = MeetingStatus.IMPORTING,
        checkedAt = Instant.EPOCH, importedAt = null, commissie = commissie,
    )

    private fun submittedExecution(type: AnalysisRunType, category: String = "C", meetingId: UUID? = null): RuntimeExecution? {
        val prepared = prepared(type).copy(category = category).let { if (meetingId == null) it else it.copy(meetingId = meetingId) }
        var submitted: RuntimeCreateRequest? = null
        `when`(repository.claimPendingRun()).thenReturn(prepared)
        doAnswer { invocation ->
            submitted = invocation.getArgument(0)
            submittedRequests += requireNotNull(submitted)
            RuntimeJob("job-${type.name}", "pvdd", prepared.run.idempotencyKey, RuntimeExecution("anthropic", "gebruikt", "SUBSCRIPTION"), "QUEUED", "QUEUED")
        }.`when`(runtime).create(anyRequest())

        orchestrator.submitOneRun()

        verify(repository).markSubmitted(prepared.run.id, "job-${type.name}", AnalysisStatus.QUEUED, "anthropic", "gebruikt")
        return submitted?.execution
    }

    private fun prepared(type: AnalysisRunType) = PreparedAnalysisRun(
        run = AnalysisRun(
            id = UUID.randomUUID(),
            agendaItemId = UUID.randomUUID(),
            sourceFingerprint = "a".repeat(64),
            promptVersion = "prompt-v1",
            selectionVersion = "selection-v1",
            idempotencyKey = "pvdd-${type.name}",
            runtimeJobId = null,
            status = AnalysisStatus.PENDING,
            errorCode = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            completedAt = null,
        ),
        meetingId = UUID.randomUUID(),
        category = "C",
        agendaItemSourceId = "item-1",
        prompt = "synthetische prompt",
        responseSchema = mapper.readTree("""{"type":"object"}"""),
        allowedSources = emptyList(),
        runType = type,
    )

    private fun anyRequest(): RuntimeCreateRequest = org.mockito.ArgumentMatchers.any(RuntimeCreateRequest::class.java)
        ?: RuntimeCreateRequest("", "", mapper.createObjectNode())
}
