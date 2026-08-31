package nl.vdzon.pvdd.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

data class GoogleIdentity(val email: String, val emailVerified: Boolean)

fun interface GoogleIdTokenVerifier {
    fun verify(idToken: String): GoogleIdentity
}

class NimbusGoogleIdTokenVerifier(
    private val clientId: String,
    jwkSource: JWKSource<SecurityContext>,
) : GoogleIdTokenVerifier {
    constructor(clientId: String) : this(
        clientId,
        JWKSourceBuilder.create<SecurityContext>(URI.create(GOOGLE_JWKS_URL).toURL()).build(),
    )

    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    }

    override fun verify(idToken: String): GoogleIdentity {
        val claims: JWTClaimsSet = try {
            processor.process(idToken, null)
        } catch (_: Exception) {
            throw unauthorized("Invalid Google ID token")
        }
        if (clientId !in claims.audience.orEmpty()) throw unauthorized("Wrong Google token audience")
        if (claims.issuer !in ACCEPTED_ISSUERS) throw unauthorized("Wrong Google token issuer")
        val expiry = claims.expirationTime?.toInstant()
        if (expiry == null || !expiry.isAfter(Instant.now())) throw unauthorized("Expired Google token")
        val email = claims.getStringClaim("email").orEmpty().trim().lowercase()
        if (email.isBlank()) throw unauthorized("Google token has no e-mail")
        return GoogleIdentity(email, claims.getBooleanClaim("email_verified") ?: false)
    }

    private fun unauthorized(message: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, message)

    companion object {
        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        val ACCEPTED_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
