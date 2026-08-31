package nl.vdzon.pvdd.meetings

import java.sql.Timestamp
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class MeetingRepository(private val jdbc: JdbcTemplate) : MeetingStore {
    override fun upsert(meeting: Meeting): UUID = jdbc.query(
        """
        INSERT INTO meeting(
            id, source_id, committee, starts_at, ends_at, location, title, source_url,
            source_hash, status, checked_at, imported_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (source_id) DO UPDATE SET
            committee = EXCLUDED.committee,
            starts_at = EXCLUDED.starts_at,
            ends_at = EXCLUDED.ends_at,
            location = EXCLUDED.location,
            title = EXCLUDED.title,
            source_url = EXCLUDED.source_url,
            source_hash = EXCLUDED.source_hash,
            status = EXCLUDED.status,
            checked_at = EXCLUDED.checked_at,
            imported_at = EXCLUDED.imported_at,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        meeting.id,
        meeting.sourceId,
        meeting.committee,
        Timestamp.from(meeting.startsAt),
        meeting.endsAt?.let(Timestamp::from),
        meeting.location,
        meeting.title,
        meeting.sourceUrl.toString(),
        meeting.sourceHash,
        meeting.status.name,
        Timestamp.from(meeting.checkedAt),
        meeting.importedAt?.let(Timestamp::from),
    ).single()

    override fun upsert(item: AgendaItem): UUID = jdbc.query(
        """
        INSERT INTO agenda_item(
            id, meeting_id, source_id, parent_source_id, sequence_number, display_number,
            category, title, explanation, treatment_proposal, source_url, source_hash,
            substantive, import_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (meeting_id, source_id) DO UPDATE SET
            parent_source_id = EXCLUDED.parent_source_id,
            sequence_number = EXCLUDED.sequence_number,
            display_number = EXCLUDED.display_number,
            category = EXCLUDED.category,
            title = EXCLUDED.title,
            explanation = EXCLUDED.explanation,
            treatment_proposal = EXCLUDED.treatment_proposal,
            source_url = EXCLUDED.source_url,
            source_hash = EXCLUDED.source_hash,
            substantive = EXCLUDED.substantive,
            import_status = EXCLUDED.import_status,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        item.id,
        item.meetingId,
        item.sourceId,
        item.parentSourceId,
        item.sequence,
        item.displayNumber,
        item.category.name,
        item.title,
        item.explanation,
        item.treatmentProposal,
        item.sourceUrl.toString(),
        item.sourceHash,
        item.substantive,
        item.importStatus.name,
    ).single()

    fun countMeetingsBySourceId(sourceId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM meeting WHERE source_id = ?",
        Int::class.java,
        sourceId,
    ) ?: 0

    fun countAgendaItems(meetingId: UUID): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM agenda_item WHERE meeting_id = ?",
        Int::class.java,
        meetingId,
    ) ?: 0

    fun findMeeting(meetingId: UUID): Meeting? = jdbc.query(
        """
        SELECT id, source_id, committee, starts_at, ends_at, location, title, source_url,
               source_hash, status, checked_at, imported_at
        FROM meeting WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            Meeting(
                id = rs.getObject("id", UUID::class.java),
                sourceId = rs.getString("source_id"),
                committee = rs.getString("committee"),
                startsAt = rs.getTimestamp("starts_at").toInstant(),
                endsAt = rs.getTimestamp("ends_at")?.toInstant(),
                location = rs.getString("location"),
                title = rs.getString("title"),
                sourceUrl = java.net.URI(rs.getString("source_url")),
                sourceHash = rs.getString("source_hash"),
                status = MeetingStatus.valueOf(rs.getString("status")),
                checkedAt = rs.getTimestamp("checked_at").toInstant(),
                importedAt = rs.getTimestamp("imported_at")?.toInstant(),
            )
        },
        meetingId,
    ).singleOrNull()

    fun findAgendaItems(meetingId: UUID): List<AgendaItem> = jdbc.query(
        """
        SELECT id, meeting_id, source_id, parent_source_id, sequence_number, display_number,
               category, title, explanation, treatment_proposal, source_url, source_hash,
               substantive, import_status
        FROM agenda_item WHERE meeting_id = ? ORDER BY sequence_number
        """.trimIndent(),
        { rs, _ ->
            AgendaItem(
                id = rs.getObject("id", UUID::class.java),
                meetingId = rs.getObject("meeting_id", UUID::class.java),
                sourceId = rs.getString("source_id"),
                parentSourceId = rs.getString("parent_source_id"),
                sequence = rs.getInt("sequence_number"),
                displayNumber = rs.getString("display_number"),
                category = AgendaCategory.valueOf(rs.getString("category")),
                title = rs.getString("title"),
                explanation = rs.getString("explanation"),
                treatmentProposal = rs.getString("treatment_proposal"),
                sourceUrl = java.net.URI(rs.getString("source_url")),
                sourceHash = rs.getString("source_hash"),
                substantive = rs.getBoolean("substantive"),
                importStatus = ImportStatus.valueOf(rs.getString("import_status")),
            )
        },
        meetingId,
    )

    override fun lastSuccessfulSourceId(): String? = jdbc.queryForList(
        "SELECT metadata_value FROM application_metadata WHERE metadata_key = 'last-successful-meeting-source-id'",
        String::class.java,
    ).firstOrNull()

    @Transactional
    override fun markSuccessful(meetingId: UUID) {
        val sourceId = jdbc.queryForObject("SELECT source_id FROM meeting WHERE id = ?", String::class.java, meetingId)
            ?: error("Meeting $meetingId does not exist")
        check(
            jdbc.update(
                """
                UPDATE meeting
                SET status = 'COMPLETE', completed_at = CURRENT_TIMESTAMP, error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                meetingId,
            ) == 1,
        )
        jdbc.update(
            """
            INSERT INTO application_metadata(metadata_key, metadata_value, updated_at)
            VALUES ('last-successful-meeting-source-id', ?, CURRENT_TIMESTAMP)
            ON CONFLICT (metadata_key) DO UPDATE
            SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
            sourceId,
        )
    }

    override fun markFailed(meetingId: UUID, errorCode: String) {
        jdbc.update(
            """
            UPDATE meeting SET status = 'FAILED', error_code = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
            errorCode,
            meetingId,
        )
    }

    override fun markAnalysing(meetingId: UUID) {
        updateStatus(meetingId, MeetingStatus.ANALYSING, null)
    }

    override fun markPartial(meetingId: UUID, errorCode: String) {
        updateStatus(meetingId, MeetingStatus.PARTIAL, errorCode)
    }

    private fun updateStatus(meetingId: UUID, status: MeetingStatus, errorCode: String?) {
        check(
            jdbc.update(
                """
                UPDATE meeting SET status = ?, error_code = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                status.name,
                errorCode,
                meetingId,
            ) == 1,
        )
    }
}

interface MeetingStore {
    fun upsert(meeting: Meeting): UUID
    fun upsert(item: AgendaItem): UUID
    fun lastSuccessfulSourceId(): String?
    fun markSuccessful(meetingId: UUID)
    fun markFailed(meetingId: UUID, errorCode: String)
    fun markAnalysing(meetingId: UUID)
    fun markPartial(meetingId: UUID, errorCode: String)
}
