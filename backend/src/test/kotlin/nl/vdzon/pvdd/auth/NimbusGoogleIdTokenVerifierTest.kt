package nl.vdzon.pvdd.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class NimbusGoogleIdTokenVerifierTest {
    private val signingKey = RSAKeyGenerator(2048).keyID("google-test-key").generate()
    private val jwkSource = JWKSource<SecurityContext> { selector, _ ->
        selector.select(JWKSet(signingKey.toPublicJWK()))
    }
    private val verifier = NimbusGoogleIdTokenVerifier(CLIENT_ID, jwkSource)

    @Test
    fun `accepts a valid RS256 Google identity`() {
        val identity = verifier.verify(token())

        assertEquals("robbertvdzon@gmail.com", identity.email)
        assertEquals(true, identity.emailVerified)
    }

    @Test
    fun `rejects a wrong audience issuer expiry and signature`() {
        assertUnauthorized { verifier.verify(token(audience = "another-client")) }
        assertUnauthorized { verifier.verify(token(issuer = "https://issuer.example")) }
        assertUnauthorized { verifier.verify(token(expiry = Instant.now().minusSeconds(1))) }

        val otherKey = RSAKeyGenerator(2048).keyID(signingKey.keyID).generate()
        assertUnauthorized { verifier.verify(token(signer = otherKey)) }
    }

    @Test
    fun `preserves the verified email claim for the authorization boundary`() {
        val identity = verifier.verify(token(emailVerified = false))

        assertFalse(identity.emailVerified)
    }

    private fun token(
        audience: String = CLIENT_ID,
        issuer: String = "https://accounts.google.com",
        expiry: Instant = Instant.now().plusSeconds(60),
        emailVerified: Boolean = true,
        signer: RSAKey = signingKey,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .audience(audience)
            .issuer(issuer)
            .expirationTime(Date.from(expiry))
            .claim("email", "RobbertVDZON@gmail.com")
            .claim("email_verified", emailVerified)
            .build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(),
            claims,
        ).apply { sign(RSASSASigner(signer)) }.serialize()
    }

    private fun assertUnauthorized(block: () -> Unit) {
        val failure = assertFailsWith<ResponseStatusException>(block = block)
        assertEquals(HttpStatus.UNAUTHORIZED, failure.statusCode)
    }

    private companion object {
        const val CLIENT_ID = "pvdd-client-id"
    }
}
