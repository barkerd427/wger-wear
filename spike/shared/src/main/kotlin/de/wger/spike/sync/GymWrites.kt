package de.wger.spike.sync

import com.powersync.PowerSyncDatabase
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** wger generates UUIDv7 for sessions/logs; the id is writable, so the device mints it offline. */
fun uuid7(nowMs: Long = System.currentTimeMillis(), rnd: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16).also(rnd::nextBytes)
    for (i in 0 until 6) bytes[i] = (nowMs ushr (40 - 8 * i)).toByte()
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    var msb = 0L
    var lsb = 0L
    for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
    for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
    return UUID(msb, lsb).toString()
}

/**
 * Starting a session with `day_id` set is what makes a `need_logs_to_advance` day advance
 * (wger manager/models/routine.py: it looks for a session for that day on the previous date).
 */
suspend fun PowerSyncDatabase.startSession(routineId: Long, dayId: Long, start: Instant): String {
    val id = uuid7(start.toEpochMilli())
    execute(
        "INSERT INTO manager_workoutsession (id, routine_id, day_id, impression, datetime_start, date) " +
            "VALUES (?, ?, ?, '2', ?, ?)",
        listOf(id, routineId, dayId, start.toString(), start.toString().substring(0, 10)),
    )
    return id
}

suspend fun PowerSyncDatabase.finishSession(sessionId: String, end: Instant) {
    execute("UPDATE manager_workoutsession SET datetime_end = ? WHERE id = ?", listOf(end.toString(), sessionId))
}

data class SetLog(
    val exerciseId: Long,
    val routineId: Long,
    val slotEntryId: Long,
    val iteration: Int,
    val reps: Double,
    val weightKg: Double,
    val rir: Double?,
    val at: Instant,
)

/** Units: wger fixtures use repetitions_unit 1 = reps, weight_unit 1 = kg. */
suspend fun PowerSyncDatabase.logSet(sessionId: String, set: SetLog): String {
    val id = uuid7(set.at.toEpochMilli())
    execute(
        "INSERT INTO manager_workoutlog (id, session_id, exercise_id, routine_id, slot_entry_id, iteration, " +
            "repetitions, repetitions_unit_id, weight, weight_unit_id, rir, date) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 1, ?, ?)",
        listOf(
            id, sessionId, set.exerciseId, set.routineId, set.slotEntryId, set.iteration,
            set.reps, set.weightKg, set.rir, set.at.toString(),
        ),
    )
    return id
}
