package de.wger.spike.phone

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset

/** What a finished wger session looks like once it reaches the phone (from PowerSync or the watch). */
data class FinishedSession(
    val wgerSessionId: String, // UUIDv7 minted on the watch
    val routineName: String,
    val start: Instant,
    val end: Instant,
    val zone: ZoneOffset,
    val notes: String?,
    val heartRate: List<Pair<Instant, Long>>,
)

/**
 * clientRecordId = wger session UUID makes the insert an idempotent upsert, so re-processing a
 * session (retries, both devices) never duplicates it in Health Connect.
 * Device type WATCH + activelyRecorded: the data really came from the watch's sensor.
 */
fun FinishedSession.toHealthConnect(): List<Record> {
    val watch = Device(type = Device.TYPE_WATCH)
    val session = ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = zone,
        endTime = end,
        endZoneOffset = zone,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        title = routineName,
        notes = notes,
        metadata = Metadata.activelyRecorded(device = watch, clientRecordId = "wger-session-$wgerSessionId", clientRecordVersion = 1),
    )
    val samples = heartRate.filter { (t, _) -> !t.isBefore(start) && !t.isAfter(end) }
    if (samples.isEmpty()) return listOf(session)
    val hr = HeartRateRecord(
        startTime = samples.first().first,
        startZoneOffset = zone,
        endTime = samples.last().first.plusMillis(1),
        endZoneOffset = zone,
        samples = samples.map { (t, bpm) -> HeartRateRecord.Sample(t, bpm) },
        metadata = Metadata.activelyRecorded(device = watch, clientRecordId = "wger-hr-$wgerSessionId", clientRecordVersion = 1),
    )
    return listOf(session, hr)
}

suspend fun HealthConnectClient.writeSession(session: FinishedSession) {
    insertRecords(session.toHealthConnect())
}
