package nl.vdzon.pvdd.analysis

import java.util.UUID
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import org.springframework.stereotype.Service

enum class AnalysisCommandStatus { QUEUED, CANCELLED, NOT_FOUND, NOT_CANCELLABLE }

data class AnalysisCommandResult(val status: AnalysisCommandStatus, val id: UUID?)

@Service
class AnalysisFacade(
    private val repository: AnalysisRepository,
    private val meetings: MeetingRepository,
    private val runtime: AgentRuntimeGateway,
) {
    fun requestMeeting(meetingId: UUID): AnalysisCommandResult {
        if (meetings.findMeeting(meetingId) == null) return AnalysisCommandResult(AnalysisCommandStatus.NOT_FOUND, null)
        repository.queueMeeting(meetingId)
        return AnalysisCommandResult(AnalysisCommandStatus.QUEUED, meetingId)
    }

    fun cancel(runId: UUID): AnalysisCommandResult {
        val run = repository.runControl(runId) ?: return AnalysisCommandResult(AnalysisCommandStatus.NOT_FOUND, null)
        if (run.status !in setOf(AnalysisStatus.PENDING, AnalysisStatus.QUEUED, AnalysisStatus.WAITING_FOR_WORKER, AnalysisStatus.RUNNING)) {
            return AnalysisCommandResult(AnalysisCommandStatus.NOT_CANCELLABLE, runId)
        }
        run.runtimeJobId?.let(runtime::cancel)
        repository.updateRuntimeStatus(runId, AnalysisStatus.CANCELLED, "USER_CANCELLED")
        meetings.markPartial(run.meetingId, "USER_CANCELLED")
        return AnalysisCommandResult(AnalysisCommandStatus.CANCELLED, runId)
    }
}
