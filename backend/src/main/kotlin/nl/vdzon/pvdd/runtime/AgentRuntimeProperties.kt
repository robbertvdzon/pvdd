package nl.vdzon.pvdd.runtime

import jakarta.annotation.PostConstruct
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.agent-runtime")
data class AgentRuntimeProperties(
    var baseUrl: URI = URI("http://localhost:18089"),
    var token: String = "local-pvdd-token",
    var vendorId: String = "mock",
    var model: String = "mock",
    var mode: String = "MOCK",
    var environment: String = "local",
    var connectTimeout: Duration = Duration.ofSeconds(1),
    var requestTimeout: Duration = Duration.ofSeconds(3),
    var uploadTimeout: Duration = Duration.ofSeconds(30),
    var selfTestTimeout: Duration = Duration.ofSeconds(15),
) {
    @PostConstruct
    fun validate() {
        require(baseUrl.scheme in setOf("http", "https")) { "Agent Runtime URL must use HTTP(S)." }
        require(baseUrl.userInfo == null && baseUrl.query == null && baseUrl.fragment == null) { "Agent Runtime URL must not contain credentials, query or fragment." }
        require(token.isNotBlank()) { "Agent Runtime token is required." }
        require(vendorId.matches(Regex("[a-z][a-z0-9-]{0,99}"))) { "Agent Runtime vendor ID is invalid." }
        require(model.isNotBlank()) { "Agent Runtime model is required." }
        require(mode in setOf("SUBSCRIPTION", "API", "MOCK")) { "Agent Runtime mode must be SUBSCRIPTION, API or MOCK." }
        require(!connectTimeout.isNegative && !connectTimeout.isZero && connectTimeout <= Duration.ofSeconds(5))
        require(!requestTimeout.isNegative && !requestTimeout.isZero && requestTimeout <= Duration.ofSeconds(30))
        require(!uploadTimeout.isNegative && !uploadTimeout.isZero && uploadTimeout <= Duration.ofMinutes(5))
        when (environment.lowercase()) {
            "local" -> Unit
            "acceptance" -> require(vendorId == "mock" && model == "mock" && mode == "MOCK") { "Acceptance must use the deterministic mocked Runtime." }
            "production" -> require(vendorId != "mock" && mode != "MOCK") { "Production must use a real Agent Runtime execution." }
            else -> error("Unknown PvdD environment: $environment")
        }
    }
}
