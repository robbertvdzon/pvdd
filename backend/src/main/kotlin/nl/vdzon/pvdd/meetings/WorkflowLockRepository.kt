package nl.vdzon.pvdd.meetings

import java.time.Duration
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class WorkflowLockRepository(private val jdbc: JdbcTemplate) : WorkflowLock {
    override fun tryAcquire(lockName: String, ownerId: UUID): Boolean {
        val lease = Duration.ofHours(2)
        require(!lease.isNegative && !lease.isZero && lease <= Duration.ofHours(4))
        return jdbc.query(
            """
            INSERT INTO workflow_lock(lock_name, owner_id, locked_until, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), CURRENT_TIMESTAMP)
            ON CONFLICT (lock_name) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                locked_until = EXCLUDED.locked_until,
                updated_at = CURRENT_TIMESTAMP
            WHERE workflow_lock.locked_until <= CURRENT_TIMESTAMP
            RETURNING owner_id
            """.trimIndent(),
            { rs, _ -> rs.getObject("owner_id", UUID::class.java) },
            lockName,
            ownerId,
            lease.toMillis(),
        ).singleOrNull() == ownerId
    }

    override fun release(lockName: String, ownerId: UUID) {
        jdbc.update(
            """
            UPDATE workflow_lock
            SET owner_id = NULL, locked_until = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE lock_name = ? AND owner_id = ?
            """.trimIndent(),
            lockName,
            ownerId,
        )
    }
}

interface WorkflowLock {
    fun tryAcquire(lockName: String, ownerId: UUID): Boolean
    fun release(lockName: String, ownerId: UUID)
}
