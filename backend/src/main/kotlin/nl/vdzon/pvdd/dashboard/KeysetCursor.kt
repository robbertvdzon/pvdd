package nl.vdzon.pvdd.dashboard

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Het ondoorzichtige cursorformaat van de leeslijsten in dit dashboard: base64url zonder padding
 * van `<instant>|<uuid>`. Het formaat is uit [AiRunQueryRepository] hierheen verplaatst zodat de
 * AI-runlijst en de lijst met voorbije vergaderingen letterlijk dezelfde codering gebruiken in
 * plaats van twee varianten die uit elkaar kunnen lopen.
 *
 * Een cursor die niet te lezen is levert [IllegalArgumentException]; de controller vertaalt dat
 * naar de foutcode van de betreffende route.
 */
internal object KeysetCursor {
    fun encode(marker: Instant, id: UUID): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$marker|$id".toByteArray(StandardCharsets.UTF_8))

    fun decode(cursor: String): Pair<Instant, UUID> = try {
        val value = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split('|')
        Instant.parse(value[0]) to UUID.fromString(value[1])
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor")
    }
}
