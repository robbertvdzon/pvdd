package nl.vdzon.pvdd.meetings

import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class AcceptanceScenarioRunnerTest {
    @Test
    fun `startup scenario is disabled by default and forbidden outside acceptance`() {
        val workflow = mock(MeetingCheckWorkflow::class.java)
        AcceptanceScenarioRunner(workflow, "production", false).runAfterStartup()
        verifyNoInteractions(workflow)
        assertFailsWith<IllegalArgumentException> {
            AcceptanceScenarioRunner(workflow, "production", true)
        }
    }

    @Test
    fun `acceptance startup uses the normal workflow exactly once`() {
        val workflow = mock(MeetingCheckWorkflow::class.java)
        `when`(workflow.check()).thenReturn(MeetingCheckResult(MeetingCheckStatus.IMPORTED, "meeting-v1"))
        AcceptanceScenarioRunner(workflow, "acceptance", true).runAfterStartup()
        verify(workflow).check()
    }
}
