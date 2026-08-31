package nl.vdzon.pvdd.system.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class VersionResponse(
    val application: String,
    val applicationVersion: String,
    val gitRevision: String,
    val buildTime: String,
    val environment: String,
    val backendBuildIdentity: String,
)

@RestController
@RequestMapping("/api/version")
class VersionController(
    @param:Value("\${spring.application.name}") private val application: String,
    @param:Value("\${pvdd.version}") private val version: String,
    @param:Value("\${pvdd.git-revision}") private val gitRevision: String,
    @param:Value("\${pvdd.build-time}") private val buildTime: String,
    @param:Value("\${pvdd.environment}") private val environment: String,
) {
    @GetMapping
    fun version(): VersionResponse {
        val shortRevision = gitRevision.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
            ?.take(12)?.lowercase() ?: "unknown"
        return VersionResponse(application, version, gitRevision, buildTime, environment, "$version+$shortRevision")
    }
}
