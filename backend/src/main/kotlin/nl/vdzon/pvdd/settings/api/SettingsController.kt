package nl.vdzon.pvdd.settings.api

import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.meetings.MutationGuard
import nl.vdzon.pvdd.settings.SettingsOverviewDto
import nl.vdzon.pvdd.settings.SettingsService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class UpdateAnalysisInstructionsRequest(val additionalInstructions: String)
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

    @PostMapping("/retry-failed-analyses")
    fun retryFailedAnalyses(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
        @RequestHeader("Idempotency-Key") key: String,
    ): RetryFailedAnalysesResponse = guard.execute(email, "retry-all-failed-analyses", key) {
        RetryFailedAnalysesResponse(settings.retryFailedAnalyses())
    }
}
