package nl.vdzon.pvdd.source

import jakarta.annotation.PostConstruct
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.meeting-source")
data class MeetingSourceProperties(
    var baseUrl: URI = URI("http://localhost:18091"),
    var environment: String = "local",
) {
    @PostConstruct
    fun validateEnvironmentBoundary() {
        require(baseUrl.userInfo == null && baseUrl.query == null && baseUrl.fragment == null) { "Meeting source URL contains forbidden components." }
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
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "meeting-source-mock", "pvdd-meeting-source-mock")
        private val PRODUCTION_URL = URI("https://noordholland.bestuurlijkeinformatie.nl")
    }
}
