package nl.vdzon.pvdd.source

import jakarta.annotation.PostConstruct
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.meeting-source")
data class MeetingSourceProperties(
    var baseUrl: URI = URI("http://localhost:18091"),
    var environment: String = "local",
    /** Alias voor het agenda-type van Ruimte; een expliciete lijstwaarde in [commissies] wint. */
    var agendaTypeId: String? = null,
    var connectTimeout: Duration = Duration.ofSeconds(2),
    var requestTimeout: Duration = Duration.ofSeconds(5),
    var maxPageBytes: Int = 2 * 1024 * 1024,
    var commissies: List<CommissieSource> = emptyList(),
) {
    /** Alle bekende commissies; een ontbrekend of leeg agenda-type is een lege `agendaTypeId`. */
    fun resolvedCommissies(): List<CommissieSource> = Commissie.entries.map { commissie ->
        val configured = commissies.firstOrNull { it.commissie == commissie }
        val typeId = configured?.agendaTypeId?.trim().orEmpty().ifEmpty {
            if (commissie == Commissie.RUIMTE) agendaTypeId?.trim().orEmpty().ifEmpty { RUIMTE_AGENDA_TYPE_ID } else ""
        }
        CommissieSource(commissie, configured?.weergavenaam?.takeIf { it.isNotBlank() } ?: commissie.defaultName, typeId)
    }

    fun agendaTypeIdOf(commissie: Commissie): String = resolvedCommissies().first { it.commissie == commissie }.agendaTypeId

    @PostConstruct
    fun validateEnvironmentBoundary() {
        require(baseUrl.userInfo == null && baseUrl.query == null && baseUrl.fragment == null) { "Meeting source URL contains forbidden components." }
        resolvedCommissies().forEach {
            require(it.agendaTypeId.isEmpty() || it.agendaTypeId.matches(Regex("[0-9]{1,20}"))) { "Meeting agenda type ID is invalid." }
        }
        require(commissies.map { it.commissie }.distinct().size == commissies.size) { "Meeting source commissie is configured twice." }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) { "Meeting source connect timeout must be positive." }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Meeting source request timeout must be positive." }
        require(maxPageBytes in 1024..5 * 1024 * 1024) { "Meeting source page limit is unsafe." }
        when (environment.lowercase()) {
            "local" -> require(baseUrl.scheme == "http" && baseUrl.host in LOCAL_HOSTS) { "Local meeting source must be the local mock." }
            "acceptance" -> require(
                baseUrl.scheme == "http" &&
                    (baseUrl.host == "pvdd-meeting-source-mock" || baseUrl.host.endsWith(".pvdd-acceptance.svc.cluster.local"))
            ) { "Acceptance meeting source must be the internal mock service." }
            "production" -> require(normalized(baseUrl) == PRODUCTION_URL) { "Production meeting source must be the approved Noord-Holland HTTPS host." }
            else -> error("Unknown PvdD environment: $environment")
        }
    }

    private fun normalized(uri: URI) = URI(uri.scheme, null, uri.host, uri.port, uri.path.trimEnd('/'), null, null)

    companion object {
        const val RUIMTE_AGENDA_TYPE_ID = "1100617069"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "meeting-source-mock", "pvdd-meeting-source-mock")
        private val PRODUCTION_URL = URI("https://noordholland.bestuurlijkeinformatie.nl")
    }
}

data class CommissieSource(
    var commissie: Commissie = Commissie.RUIMTE,
    var weergavenaam: String = "",
    var agendaTypeId: String = "",
)
