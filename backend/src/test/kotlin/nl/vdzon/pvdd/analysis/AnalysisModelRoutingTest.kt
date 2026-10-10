package nl.vdzon.pvdd.analysis

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.policy.PolicyImportService
import nl.vdzon.pvdd.policy.PolicySelector
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import nl.vdzon.pvdd.runtime.AgentRuntimeProperties
import nl.vdzon.pvdd.runtime.RuntimeCreateRequest
import nl.vdzon.pvdd.runtime.RuntimeExecution
import nl.vdzon.pvdd.runtime.RuntimeJob
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.module.kotlin.jacksonObjectMapper

class AnalysisModelRoutingTest {
    private val mapper = jacksonObjectMapper()
    private val notesModel = RuntimeExecution("anthropic", "claude-haiku-4-5-20251001", "SUBSCRIPTION")
    private val adviceModel = RuntimeExecution("anthropic", "claude-opus-5", "SUBSCRIPTION")

    private val repository = mock(AnalysisRepository::class.java)
    private val runtime = mock(AgentRuntimeGateway::class.java)
    private val models = mock(AnalysisModelSettings::class.java).also {
        `when`(it.execution(AnalysisModelTask.SOURCE_NOTES)).thenReturn(notesModel)
        `when`(it.execution(AnalysisModelTask.FINAL_ADVICE)).thenReturn(adviceModel)
    }
    private val orchestrator = AnalysisOrchestrator(
        repository,
        mock(MeetingRepository::class.java),
        mock(DocumentRepository::class.java),
        mock(PolicyImportService::class.java),
        mock(PolicySelector::class.java),
        mock(PolicyPositionCatalogue::class.java),
        mock(AnalysisGuidanceService::class.java),
        mock(PromptBuilder::class.java),
        mock(ContentResultValidator::class.java),
        runtime,
        AgentRuntimeProperties(),
        models,
        mapper,
        Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `source notes and final advice are submitted with the model chosen for their task`() {
        assertEquals(notesModel, submittedExecution(AnalysisRunType.SOURCE_NOTES))
        assertEquals(adviceModel, submittedExecution(AnalysisRunType.FINAL_ADVICE))
    }

    private fun submittedExecution(type: AnalysisRunType): RuntimeExecution? {
        val prepared = prepared(type)
        var submitted: RuntimeCreateRequest? = null
        `when`(repository.claimPendingRun()).thenReturn(prepared)
        `when`(runtime.create(anyRequest())).thenAnswer { invocation ->
            submitted = invocation.getArgument(0)
            RuntimeJob("job-${type.name}", "pvdd", prepared.run.idempotencyKey, RuntimeExecution("anthropic", "gebruikt", "SUBSCRIPTION"), "QUEUED", "QUEUED")
        }

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
