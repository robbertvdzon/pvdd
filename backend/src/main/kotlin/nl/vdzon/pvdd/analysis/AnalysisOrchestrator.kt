package nl.vdzon.pvdd.analysis

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentPassage
import nl.vdzon.pvdd.documents.DocumentRepository
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaItem
import nl.vdzon.pvdd.meetings.MeetingCheckStatus
import nl.vdzon.pvdd.meetings.MeetingImportedEvent
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.SourceState
import nl.vdzon.pvdd.policy.PolicyImportService
import nl.vdzon.pvdd.policy.PolicySelector
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import nl.vdzon.pvdd.runtime.AgentRuntimeProperties
import nl.vdzon.pvdd.runtime.RuntimeCreateRequest
import nl.vdzon.pvdd.runtime.RuntimeJob
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class AnalysisOrchestrator(
    private val repository: AnalysisRepository,
    private val meetings: MeetingRepository,
    private val documents: DocumentRepository,
    private val policyImport: PolicyImportService,
    private val policySelector: PolicySelector,
    private val prompts: PromptBuilder,
    private val resultValidator: ContentResultValidator,
    private val runtime: AgentRuntimeGateway,
    private val runtimeProperties: AgentRuntimeProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun queuePromptUpgrade() {
        val queued = repository.queueMeetingsMissingPromptVersion(PromptBuilder.PROMPT_VERSION)
        if (queued > 0) log.info("Queued {} meeting(s) for prompt {}", queued, PromptBuilder.PROMPT_VERSION)
    }

    @EventListener
    fun meetingImported(event: MeetingImportedEvent) {
        repository.queueMeeting(event.meetingId)
    }

    @Scheduled(
        fixedDelayString = "\${pvdd.analysis.reconcile-delay:5s}",
        initialDelayString = "\${pvdd.analysis.reconcile-delay:5s}",
    )
    fun reconcile() {
        prepareOneMeeting()
        activateReadySynthesisRuns()
        submitOneRun()
        repository.activeRuns().forEach(::reconcileRun)
    }

    fun prepareOneMeeting() {
        val meetingId = repository.claimMeeting() ?: return
        try {
            policyImport.ensureImported()
            val meeting = requireNotNull(meetings.findMeeting(meetingId))
            val items = meetings.findAgendaItems(meetingId)
                .filter {
                    it.sourceState != SourceState.WITHDRAWN && it.substantive &&
                        it.category in setOf(AgendaCategory.A, AgendaCategory.B, AgendaCategory.C)
                }
            require(items.isNotEmpty()) { "NO_ANALYSIS_ITEMS" }
            items.forEach { item ->
                val sources = sources(item)
                val plan = prompts.plan(item.toAnalysisItem(), sources)
                val fingerprint = fingerprint(item, sources, meeting.publicationStatus.name)
                val key = "pvdd-${sha256("${meeting.sourceId}|${item.sourceId}|$fingerprint|${PromptBuilder.PROMPT_VERSION}")}" 
                val now = clock.instant()
                val finalRunId = UUID.randomUUID()
                val finalRun = preparedRun(
                    runId = finalRunId,
                    item = item,
                    meetingId = meetingId,
                    fingerprint = fingerprint,
                    key = key,
                    now = now,
                    prompt = plan.phases.singleOrNull()?.takeIf { it.type == PromptPhaseType.DIRECT_ADVICE }?.prompt,
                    schema = prompts.schema(),
                    sources = sources,
                )
                val notePhases = plan.phases.filter { it.type == PromptPhaseType.SOURCE_NOTES }
                if (notePhases.isEmpty()) {
                    repository.createPreparedRun(finalRun.copy(prompt = requireNotNull(finalRun.prompt)))
                } else {
                    val sourceById = sources.associateBy(AnalysisSource::sourceId)
                    val noteRuns = notePhases.mapIndexed { index, phase ->
                        preparedRun(
                            runId = UUID.randomUUID(),
                            item = item,
                            meetingId = meetingId,
                            fingerprint = fingerprint,
                            key = "$key-notes-${index + 1}",
                            now = now,
                            prompt = requireNotNull(phase.prompt),
                            schema = prompts.sourceNotesSchema(),
                            sources = phase.sourceIds.map { requireNotNull(sourceById[it]) },
                            runType = AnalysisRunType.SOURCE_NOTES,
                            phaseIndex = index + 1,
                            parentRunId = finalRunId,
                        )
                    }
                    repository.createPhasedRuns(finalRun, noteRuns)
                }
            }
            repository.finishMeetingPreparation(meetingId)
            // A source revision can require a fresh actuality decision while all
            // current item fingerprints still map to already successful runs.
            // In that case no runtime callback will arrive to close the meeting.
            if (repository.allRequiredRunsSucceeded(meetingId)) meetings.markSuccessful(meetingId)
        } catch (failure: Exception) {
            log.warn("Analysis preparation failed for meeting {} with {}", meetingId, safeCode(failure), failure)
            repository.retryMeetingPreparation(meetingId, safeCode(failure))
        }
    }

    fun submitOneRun() {
        val prepared = repository.claimPendingRun() ?: return
        try {
            val job = runtime.create(
                RuntimeCreateRequest(
                    idempotencyKey = prepared.run.idempotencyKey,
                    prompt = requireNotNull(prepared.prompt),
                    responseSchema = prepared.responseSchema,
                    environmentKeys = emptyList(),
                ),
            )
            repository.markSubmitted(prepared.run.id, job.id, job.status.toActiveStatus())
        } catch (failure: Exception) {
            log.warn("Runtime submit failed for analysis {} with {}", prepared.run.id, safeCode(failure))
            repository.retrySubmit(prepared.run.id, "RUNTIME_UNAVAILABLE")
        }
    }

    private fun reconcileRun(prepared: PreparedAnalysisRun) {
        val jobId = prepared.run.runtimeJobId ?: return
        try {
            val job = runtime.status(jobId)
            when (job.status) {
                "SUCCEEDED" -> complete(prepared, job)
                "FAILED" -> fail(prepared, AnalysisStatus.FAILED, job.errorCode ?: "RUNTIME_FAILED")
                "CANCELLED" -> fail(prepared, AnalysisStatus.CANCELLED, job.errorCode ?: "RUNTIME_CANCELLED")
                else -> repository.updateRuntimeStatus(prepared.run.id, job.status.toActiveStatus())
            }
        } catch (failure: Exception) {
            log.warn("Runtime reconciliation failed for analysis {} with {}", prepared.run.id, safeCode(failure))
        }
    }

    private fun complete(prepared: PreparedAnalysisRun, job: RuntimeJob) {
        try {
            val result = runtime.result(requireNotNull(prepared.run.runtimeJobId)).result
            resultValidator.validate(result)
            if (prepared.runType == AnalysisRunType.SOURCE_NOTES) {
                repository.completeSourceNotes(prepared.run.id, result, clock.instant())
                activateReadySynthesisRuns()
            } else {
                repository.completeWithAdvice(
                    prepared,
                    result,
                    mapper.createArrayNode(),
                    job.provider,
                    job.model,
                    clock.instant(),
                )
                if (repository.allRequiredRunsSucceeded(prepared.meetingId)) meetings.markSuccessful(prepared.meetingId)
            }
        } catch (failure: ContentResultValidationException) {
            log.warn("AI result validation failed for analysis {}", prepared.run.id)
            fail(prepared, AnalysisStatus.FAILED, "INVALID_RESULT")
        }
    }

    private fun activateReadySynthesisRuns() {
        repository.readySynthesisRuns().forEach { finalRun ->
            val notes = repository.sourceNoteResults(finalRun.run.id)
            repository.activateSynthesis(
                finalRun.run.id,
                prompts.synthesisPrompt(finalRun.agendaItemSourceId, finalRun.category, notes),
            )
        }
    }

    private fun fail(prepared: PreparedAnalysisRun, status: AnalysisStatus, errorCode: String) {
        repository.updateRuntimeStatus(prepared.run.id, status, errorCode)
        prepared.parentRunId?.let { repository.failParentFinal(it, errorCode) }
        meetings.markPartial(prepared.meetingId, errorCode)
    }

    private fun preparedRun(
        runId: UUID,
        item: AgendaItem,
        meetingId: UUID,
        fingerprint: String,
        key: String,
        now: java.time.Instant,
        prompt: String?,
        schema: JsonNode,
        sources: List<AnalysisSource>,
        runType: AnalysisRunType = AnalysisRunType.FINAL_ADVICE,
        phaseIndex: Int = 0,
        parentRunId: UUID? = null,
    ) = PreparedAnalysisRun(
        run = AnalysisRun(
            id = runId,
            agendaItemId = item.id,
            sourceFingerprint = fingerprint,
            promptVersion = PromptBuilder.PROMPT_VERSION,
            selectionVersion = PromptBuilder.SELECTION_VERSION,
            idempotencyKey = key,
            runtimeJobId = null,
            status = AnalysisStatus.PENDING,
            errorCode = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        ),
        meetingId = meetingId,
        category = item.category.name,
        agendaItemSourceId = item.sourceId,
        prompt = prompt,
        responseSchema = schema,
        allowedSources = sources,
        runType = runType,
        phaseIndex = phaseIndex,
        parentRunId = parentRunId,
    )

    private fun sources(item: AgendaItem): List<AnalysisSource> {
        val agendaText = listOfNotNull(item.title, item.explanation, item.treatmentProposal).joinToString("\n")
        val documentSources = documents.findPassagesForAnalysis(item.id).map(::documentSource)
        val selection = policySelector.select("$agendaText\n${documentSources.joinToString("\n") { it.text }}")
        val policySources = selection.chunks.map { chunk ->
            AnalysisSource(
                sourceId = "policy-p${chunk.pageNumber}-c${chunk.sequence}",
                sourceType = CitationSourceType.POLICY_PROGRAMME,
                sourceUrl = chunk.sourceUrl,
                pageNumber = chunk.pageNumber,
                section = chunk.heading,
                text = chunk.text,
            )
        }
        return listOf(
            AnalysisSource(
                sourceId = "agenda-${item.sourceId}".take(160),
                sourceType = CitationSourceType.MEETING_DOCUMENT,
                sourceUrl = item.sourceUrl,
                pageNumber = null,
                section = "Agendapunt",
                text = agendaText,
            ),
        ) + documentSources + policySources
    }

    private fun documentSource(passage: DocumentPassage): AnalysisSource = AnalysisSource(
        sourceId = "doc-${passage.documentSourceId.take(110)}-s${passage.sequence}".take(160),
        sourceType = CitationSourceType.MEETING_DOCUMENT,
        sourceUrl = passage.sourceUrl,
        pageNumber = passage.pageNumber,
        section = passage.heading,
        text = passage.text,
    )

    private fun fingerprint(
        item: AgendaItem,
        sources: List<AnalysisSource>,
        publicationStatus: String,
    ): String = analysisFingerprint(item, sources, publicationStatus)

    private fun AgendaItem.toAnalysisItem() = AnalysisAgendaItem(sourceId, category.name, title, explanation, treatmentProposal)

    private fun String.toActiveStatus(): AnalysisStatus = when (this) {
        "QUEUED", "SUCCEEDED" -> AnalysisStatus.QUEUED
        "WAITING_FOR_WORKER" -> AnalysisStatus.WAITING_FOR_WORKER
        "RUNNING" -> AnalysisStatus.RUNNING
        else -> AnalysisStatus.QUEUED
    }

    private fun safeCode(failure: Exception): String = failure.message
        ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,120}")) }
        ?: failure::class.java.simpleName.uppercase().take(120)

    companion object {
        private val log = LoggerFactory.getLogger(AnalysisOrchestrator::class.java)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun analysisFingerprint(
    item: AgendaItem,
    sources: List<AnalysisSource>,
    publicationStatus: String = "CURRENT",
): String =
    MessageDigest.getInstance("SHA-256").digest(
        buildString {
            appendFingerprintPart(publicationStatus)
            appendFingerprintPart(item.category.name)
            appendFingerprintPart(item.title)
            appendFingerprintPart(item.explanation.orEmpty())
            appendFingerprintPart(item.treatmentProposal.orEmpty())
            sources.sortedBy(AnalysisSource::sourceId).forEach { source ->
                val sourceHash = MessageDigest.getInstance("SHA-256")
                    .digest(source.text.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                appendFingerprintPart(source.sourceType.name)
                appendFingerprintPart(source.sourceId)
                appendFingerprintPart(sourceHash)
            }
        }.toByteArray(),
    ).joinToString("") { "%02x".format(it) }

private fun StringBuilder.appendFingerprintPart(value: String) {
    append(value.length).append(':').append(value)
}
