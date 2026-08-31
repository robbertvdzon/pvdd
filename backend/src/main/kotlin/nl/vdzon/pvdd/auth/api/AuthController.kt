package nl.vdzon.pvdd.auth.api

import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AuthenticatedUserResponse(val email: String)

@RestController
@RequestMapping("/api/auth")
class AuthController {
    @GetMapping("/me")
    fun me(
        @RequestAttribute(ApiAuthenticationFilter.AUTHENTICATED_EMAIL_ATTRIBUTE) email: String,
    ): AuthenticatedUserResponse = AuthenticatedUserResponse(email)
}
