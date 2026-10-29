package nl.vdzon.pvdd.meetings

import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import nl.vdzon.pvdd.source.Commissie
import nl.vdzon.pvdd.source.CommissieSource
import nl.vdzon.pvdd.source.MeetingSourceClient
import nl.vdzon.pvdd.source.MeetingSourceProperties
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Testvoorziening: start de echte acceptatiebronmock (`meeting-source-mock/server.py`) op een vrije lokale
 * poort. Alle verkeer loopt over echte HTTP, door de productie-`MeetingSourceClient` en
 * `MeetingDiscoveryService`/`AgendaParser`. Zonder `python3` wordt de test overgeslagen. Sluit af met [close].
 */
class BestuurMockServer : AutoCloseable {
    private val process: Process
    val baseUrl: URI
    private val http = HttpClient.newHttpClient()

    init {
        val directory = File("../meeting-source-mock").absoluteFile.normalize()
        assumeTrue(File(directory, "server.py").isFile, "meeting-source-mock ontbreekt")
        val port = ServerSocket(0).use { it.localPort }
        process = try {
            ProcessBuilder("python3", "server.py").directory(directory).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).apply { environment()["PORT"] = port.toString() }.start()
        } catch (_: java.io.IOException) {
            assumeTrue(false, "python3 is niet beschikbaar")
            throw IllegalStateException()
        }
        baseUrl = URI("http://127.0.0.1:$port")
        val deadline = System.nanoTime() + 15_000_000_000L
        while (runCatching { request("GET", "/health/live").first }.getOrDefault(0) != 200) {
            check(System.nanoTime() < deadline && process.isAlive) { "De bronmock start niet" }
            Thread.sleep(50)
        }
    }

    val properties = MeetingSourceProperties(
        baseUrl = baseUrl,
        environment = "local",
        commissies = listOf(CommissieSource(Commissie.BESTUUR, "Bestuur", "1100617071")),
    )

    fun discovery(now: Instant = Instant.parse("2026-10-29T10:00:00Z")) = MeetingDiscoveryService(
        source = MeetingSourceClient(properties, http),
        properties = properties.copy(),
        parser = AgendaParser(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    fun request(method: String, path: String, body: String? = null): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(baseUrl.resolve(path))
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** Kiest een scenario zoals de bestaande scenariobediening (POST /api/scenarios/<naam>). */
    fun select(name: String) {
        val (status, body) = request("POST", "/api/scenarios/$name")
        check(status == 200) { "Scenario $name kiezen gaf $status: $body" }
    }

    /** Wijzigt de inhoud van een stuk via de bestaande documentroute (PUT /api/scenarios/documents/<id>). */
    fun changeDocument(documentId: String) {
        check(request("PUT", "/api/scenarios/documents/$documentId").first == 200)
    }

    fun documentText(documentId: String): String = request("GET", "/Document/View/$documentId").second

    override fun close() {
        process.destroy()
        process.waitFor()
    }
}
