package nl.vdzon.pvdd.policy

import jakarta.annotation.PostConstruct
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pvdd.policy-sync")
data class PolicySyncProperties(
    var environment: String = "local",
    var startUrls: List<URI> = listOf(URI("http://localhost:18091/fixtures/policy.pdf")),
    var maxPages: Int = 250,
    var maxHtmlBytes: Int = 5 * 1024 * 1024,
    var maxPdfBytes: Int = 25 * 1024 * 1024,
    var maxTotalBytes: Int = 150 * 1024 * 1024,
) {
    @PostConstruct
    fun validate() {
        require(startUrls.isNotEmpty() && startUrls.size <= 20)
        require(maxPages in 1..250)
        require(maxHtmlBytes in 1024..5 * 1024 * 1024)
        require(maxPdfBytes in 1024..25 * 1024 * 1024)
        require(maxTotalBytes in maxPdfBytes..150 * 1024 * 1024)
        startUrls.forEach(::validateUrl)
    }

    fun validateUrl(url: URI) {
        require(url.userInfo == null && url.fragment == null)
        when (environment.lowercase()) {
            "local" -> require(url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1"))
            "acceptance" -> require(
                url.scheme == "http" &&
                    (url.host == "pvdd-meeting-source-mock" || url.host.endsWith(".pvdd-acceptance.svc.cluster.local")),
            )
            "production" -> require(
                url.scheme == "https" && url.port == -1 && url.host.lowercase() in OFFICIAL_HOSTS,
            )
            else -> error("Unknown PvdD environment: $environment")
        }
    }

    fun mayDiscover(url: URI): Boolean {
        if (environment.lowercase() != "production") return false
        if (url.scheme != "https" || url.host.lowercase() != WEBSITE_HOST || url.query != null) return false
        return DISCOVERY_PREFIXES.any { url.path == it || url.path.startsWith("$it/") }
    }

    companion object {
        const val WEBSITE_HOST = "noordholland.partijvoordedieren.nl"
        val OFFICIAL_HOSTS = setOf(WEBSITE_HOST, "assets.partijvoordedieren.nl")
        val DISCOVERY_PREFIXES = listOf(
            "/onze-idealen", "/standpunten", "/nieuws", "/bijdragen",
            "/initiatiefvoorstellen", "/moties", "/vragen",
        )
    }
}
