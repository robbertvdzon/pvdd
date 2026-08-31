package nl.vdzon.pvdd.system.api

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class VersionControllerTest {
    @Test
    fun `version exposes build identity without inventing a revision`() {
        val response = VersionController("pvdd", "0.1.0", "local", "unknown", "local").version()
        assertEquals("0.1.0+unknown", response.backendBuildIdentity)
    }
}
