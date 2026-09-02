package nl.vdzon.pvdd.settings

import java.time.Instant
import nl.vdzon.pvdd.analysis.AnalysisGuidanceService
import nl.vdzon.pvdd.analysis.PromptBuilder
import nl.vdzon.pvdd.meetings.MeetingCheckScheduler
import nl.vdzon.pvdd.policy.PolicySourceProperties
import nl.vdzon.pvdd.policy.PolicySyncProperties
import nl.vdzon.pvdd.policy.PolicySyncService
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

data class ScheduledJobSettingDto(
    val key: String,
    val name: String,
    val kind: String,
    val schedule: String,
    val timeZone: String?,
    val explanation: String,
)

data class PolicySourceSettingsDto(
    val programmeUrl: String,
    val startUrls: List<String>,
    val websiteHost: String,
    val discoveryPaths: List<String>,
    val allowedHosts: List<String>,
    val maximumPages: Int,
)

data class AnalysisPromptSettingsDto(
    val promptVersion: String,
    val systemPrompt: String,
    val additionalInstructions: String,
    val additionalInstructionsUpdatedAt: Instant,
    val additionalInstructionsUpdatedBy: String,
    val maximumAdditionalInstructionCharacters: Int,
)

data class SettingsOverviewDto(
    val scheduledJobs: List<ScheduledJobSettingDto>,
    val policySources: PolicySourceSettingsDto,
    val analysisPrompt: AnalysisPromptSettingsDto,
)

@Service
class SettingsService(
    private val policySource: PolicySourceProperties,
    private val policySync: PolicySyncProperties,
    private val prompts: PromptBuilder,
    private val guidance: AnalysisGuidanceService,
    private val environment: Environment,
) {
    fun overview(): SettingsOverviewDto {
        val currentGuidance = guidance.current()
        return SettingsOverviewDto(
            scheduledJobs = scheduledJobs(),
            policySources = PolicySourceSettingsDto(
                programmeUrl = policySource.url.toString(),
                startUrls = policySync.startUrls.map(Any::toString),
                websiteHost = PolicySyncProperties.WEBSITE_HOST,
                discoveryPaths = PolicySyncProperties.DISCOVERY_PREFIXES,
                allowedHosts = PolicySyncProperties.OFFICIAL_HOSTS.sorted(),
                maximumPages = policySync.maxPages,
            ),
            analysisPrompt = AnalysisPromptSettingsDto(
                promptVersion = PromptBuilder.PROMPT_VERSION,
                systemPrompt = prompts.systemPrompt(),
                additionalInstructions = currentGuidance.text,
                additionalInstructionsUpdatedAt = currentGuidance.updatedAt,
                additionalInstructionsUpdatedBy = currentGuidance.updatedBy,
                maximumAdditionalInstructionCharacters = AnalysisGuidanceService.MAX_CHARACTERS,
            ),
        )
    }

    fun updateAnalysisInstructions(text: String, email: String): SettingsOverviewDto {
        guidance.update(text, email)
        return overview()
    }

    private fun scheduledJobs(): List<ScheduledJobSettingDto> = listOf(
        ScheduledJobSettingDto(
            "meeting-check", "Vergaderingen en agenda controleren", "CRON",
            MeetingCheckScheduler.CRON, MeetingCheckScheduler.ZONE,
            "Elke dag om 05:00; gewijzigde agendapunten worden opnieuw aangeboden voor analyse.",
        ),
        ScheduledJobSettingDto(
            "policy-sync", "PvdD-standpunten actualiseren", "CRON",
            PolicySyncService.MONTHLY_CRON, PolicySyncService.SCHEDULE_ZONE,
            "Elke eerste dag van de maand om 03:30; ook handmatig te starten bij Standpunten.",
        ),
        ScheduledJobSettingDto(
            "analysis-worker", "AI-wachtrij verwerken", "FIXED_DELAY",
            environment.getProperty("pvdd.analysis.reconcile-delay", "5s"), null,
            "Technische verwerker voor het starten en volgen van AI-runs.",
        ),
        ScheduledJobSettingDto(
            "policy-worker", "Standpuntenwachtrij verwerken", "FIXED_DELAY",
            environment.getProperty("pvdd.policy-sync.reconcile-delay", "10s"), null,
            "Technische verwerker voor het ophalen en verwerken van standpunten.",
        ),
        ScheduledJobSettingDto(
            "prompt-upgrade", "Promptversie controleren", "FIXED_DELAY",
            environment.getProperty("pvdd.analysis.prompt-upgrade-delay", "1m"), null,
            "Controleert toekomstige vergaderingen op een nog niet gebruikte promptversie.",
        ),
    )
}
