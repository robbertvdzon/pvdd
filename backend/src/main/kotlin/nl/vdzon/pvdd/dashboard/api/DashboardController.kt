package nl.vdzon.pvdd.dashboard.api

import java.util.UUID
import nl.vdzon.pvdd.analysis.AnalysisCommandStatus
import nl.vdzon.pvdd.analysis.AnalysisFacade
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.dashboard.AgendaItemDetailDto
import nl.vdzon.pvdd.dashboard.AgendaItemSummaryDto
import nl.vdzon.pvdd.dashboard.AnalysisRunDto
import nl.vdzon.pvdd.dashboard.AiRunQueryRepository
import nl.vdzon.pvdd.dashboard.LogicalAiRunDetailDto
import nl.vdzon.pvdd.dashboard.LogicalAiRunPageDto
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.MeetingOverviewDto
import nl.vdzon.pvdd.meetings.MutationGuard
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class DashboardController(
    private val dashboard: DashboardRepository,
    private val analyses: AnalysisFacade,
    private val guard: MutationGuard,
    private val aiRuns: AiRunQueryRepository,
) {
    @GetMapping("/meetings/next")
    fun next(): MeetingOverviewDto = dashboard.overview()

    @GetMapping("/meetings/{id}/agenda-items")
    fun items(@PathVariable id: UUID): List<AgendaItemSummaryDto> = dashboard.agendaItems(id) ?: notFound()

    @GetMapping("/agenda-items/{id}")
    fun item(@PathVariable id: UUID): AgendaItemDetailDto = dashboard.item(id) ?: notFound()

    @GetMapping("/analysis-runs/{id}")
    fun run(@PathVariable id: UUID): AnalysisRunDto = dashboard.run(id) ?: notFound()

    @GetMapping("/ai-runs")
    fun aiRuns(
        @RequestParam state: String,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): LogicalAiRunPageDto = try {
        aiRuns.page(state, limit, cursor)
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_ai_run_query")
    }

    @GetMapping("/ai-runs/{id}")
    fun aiRun(@PathVariable id: UUID): LogicalAiRunDetailDto = aiRuns.detail(id) ?: notFound()

    @PostMapping("/meetings/{id}/analyses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestAnalysis(
        @PathVariable id: UUID,
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") key: String,
    ) = guard.execute(email, "analyse-$id", key) {
        analyses.requestMeeting(id).also { if (it.status == AnalysisCommandStatus.NOT_FOUND) notFound<Nothing>() }
    }

    @PostMapping("/analysis-runs/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") key: String,
    ) = guard.execute(email, "cancel-$id", key) {
        analyses.cancel(id).also {
            when (it.status) {
                AnalysisCommandStatus.NOT_FOUND -> notFound<Nothing>()
                AnalysisCommandStatus.NOT_CANCELLABLE -> throw ResponseStatusException(HttpStatus.CONFLICT, "not_cancellable")
                else -> Unit
            }
        }
    }

    @PostMapping("/agenda-items/{id}/retry-analysis")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryAgendaItem(
        @PathVariable id: UUID,
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") key: String,
    ) = guard.execute(email, "retry-agenda-analysis-$id", key) {
        analyses.retryAgendaItem(id).also {
            when (it.status) {
                AnalysisCommandStatus.NOT_RETRYABLE -> throw ResponseStatusException(HttpStatus.CONFLICT, "not_retryable")
                else -> Unit
            }
        }
    }

    private fun <T> notFound(): T = throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
