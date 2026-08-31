package nl.vdzon.pvdd.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GoogleAuthConfiguration {
    @Bean
    fun googleIdTokenVerifier(config: AuthConfig): GoogleIdTokenVerifier =
        NimbusGoogleIdTokenVerifier(config.googleClientId)
}
