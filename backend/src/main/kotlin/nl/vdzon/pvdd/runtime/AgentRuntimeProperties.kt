package nl.vdzon.pvdd.runtime

import jakarta.annotation.PostConstruct
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.agent-runtime")
data class AgentRuntimeProperties(
    var baseUrl: URI = URI("http://localhost:18089"),
    var token: String = "local-pvdd-token",
    var provider: String = "MOCKED",
    var model: String = "mock-model",
    var environment: String = "local",
    var connectTimeout: Duration = Duration.ofSeconds(1),
    var requestTimeout: Duration = Duration.ofSeconds(3),
    var selfTestTimeout: Duration = Duration.ofSeconds(15),
) {
    @PostConstruct
    fun validate() {
        require(baseUrl.scheme in setOf("http", "https")) { "Agent Runtime URL must use HTTP(S)." }
        require(baseUrl.userInfo == null && baseUrl.query == null && baseUrl.fragment == null) { "Agent Runtime URL must not contain credentials, query or fragment." }
        require(token.isNotBlank()) { "Agent Runtime token is required." }
        require(provider.isNotBlank() && model.isNotBlank()) { "Agent Runtime provider and model are required." }
        require(!connectTimeout.isNegative && !connectTimeout.isZero && connectTimeout <= Duration.ofSeconds(5))
        require(!requestTimeout.isNegative && !requestTimeout.isZero && requestTimeout <= Duration.ofSeconds(30))
        when (environment.lowercase()) {
            "local" -> Unit
            "acceptance" -> require(provider == "MOCKED" && model == "mock-model") { "Acceptance must use the deterministic mocked Runtime." }
            "production" -> require(provider != "MOCKED") { "Production must use a real Agent Runtime provider." }
            else -> error("Unknown PvdD environment: $environment")
        }
    }
}
