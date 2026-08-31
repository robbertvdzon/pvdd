package nl.vdzon.pvdd.policy

import jakarta.annotation.PostConstruct
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.policy-source")
data class PolicySourceProperties(
    var url: URI = URI("http://localhost:18091/fixtures/policy.pdf"),
    var environment: String = "local",
    var maxBytes: Int = 10 * 1024 * 1024,
    var chunkCharacters: Int = 2_500,
) {
    @PostConstruct
    fun validate() {
        require(url.userInfo == null && url.query == null && url.fragment == null)
        require(maxBytes in 1024..25 * 1024 * 1024)
        require(chunkCharacters in 500..5_000)
        when (environment.lowercase()) {
            "local" -> require(url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1"))
            "acceptance" -> require(
                url.scheme == "http" &&
                    (url.host == "pvdd-meeting-source-mock" || url.host.endsWith(".pvdd-acceptance.svc.cluster.local")),
            )
            "production" -> require(url == OFFICIAL_PROGRAMME_URL)
            else -> error("Unknown PvdD environment: $environment")
        }
    }

    companion object {
        val OFFICIAL_PROGRAMME_URL = URI("https://assets.partijvoordedieren.nl/assets/site/noordHolland/PvdDNH-programma-PS23.pdf")
    }
}
