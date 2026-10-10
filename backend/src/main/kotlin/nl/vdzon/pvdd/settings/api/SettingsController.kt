package nl.vdzon.pvdd.settings.api

import nl.vdzon.pvdd.analysis.AnalysisExecution
import nl.vdzon.pvdd.analysis.AnalysisModelCatalogUnavailableException
import nl.vdzon.pvdd.analysis.AnalysisModelTask
import nl.vdzon.pvdd.analysis.UnknownAnalysisModelException
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.meetings.MutationGuard
import nl.vdzon.pvdd.settings.SettingsOverviewDto
import nl.vdzon.pvdd.settings.SettingsService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class UpdateAnalysisInstructionsRequest(val additionalInstructions: String)
data class SelectAnalysisModelRequest(val vendorId: String, val model: String, val mode: String)
data class RetryFailedAnalysesResponse(val retriedCount: Int)

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val settings: SettingsService,
    private val guard: MutationGuard,
) {
    @GetMapping
    fun overview(): SettingsOverviewDto = settings.overview()

    @PutMapping("/analysis-instructions")
    fun updateAnalysisInstructions(
        @RequestBody request: UpdateAnalysisInstructionsRequest,
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
    ): SettingsOverviewDto = try {
        settings.updateAnalysisInstructions(request.additionalInstructions, email)
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_analysis_instructions")
    }

    @PutMapping("/analysis-models/{task}")
    fun selectAnalysisModel(
        @PathVariable task: String,
        @RequestBody request: SelectAnalysisModelRequest,
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
    ): SettingsOverviewDto {
        val execution = AnalysisExecution(request.vendorId.trim(), request.model.trim(), request.mode.trim().uppercase())
        if (execution.vendorId.isBlank() || execution.model.isBlank() || execution.mode.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_analysis_model")
        }
        return try {
            settings.selectAnalysisModel(modelTask(task), execution, email)
        } catch (_: UnknownAnalysisModelException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown_analysis_model")
        } catch (_: AnalysisModelCatalogUnavailableException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "runtime_catalog_unavailable")
        }
    }

    @DeleteMapping("/analysis-models/{task}")
    fun resetAnalysisModel(@PathVariable task: String): SettingsOverviewDto =
        settings.resetAnalysisModel(modelTask(task))

    private fun modelTask(value: String): AnalysisModelTask =
        AnalysisModelTask.entries.firstOrNull { it.name.equals(value.replace('-', '_'), ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_analysis_task")

    @PostMapping("/retry-failed-analyses")
    fun retryFailedAnalyses(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") key: String,
    ): RetryFailedAnalysesResponse = guard.execute(email, "retry-all-failed-analyses", key) {
        RetryFailedAnalysesResponse(settings.retryFailedAnalyses())
    }
}
