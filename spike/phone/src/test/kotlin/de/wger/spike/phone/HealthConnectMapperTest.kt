package de.wger.spike.phone

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectMapperTest {
    private val start = Instant.parse("2026-10-20T17:00:00Z")
    private fun session(hr: List<Pair<Instant, Long>>) = FinishedSession(
        wgerSessionId = "0192f0c0-0000-7000-8000-000000000001",
        routineName = "Push A",
        start = start,
        end = start.plusSeconds(3600),
        zone = ZoneOffset.ofHours(2),
        notes = null,
        heartRate = hr,
    )

    @Test
    fun mapsSessionAndHeartRateWithStableClientIds() {
        val records = session(listOf(start.minusSeconds(5) to 70L, start.plusSeconds(60) to 95L, start.plusSeconds(120) to 120L))
            .toHealthConnect()
        val ex = records[0] as ExerciseSessionRecord
        val hr = records[1] as HeartRateRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING, ex.exerciseType)
        assertEquals("wger-session-0192f0c0-0000-7000-8000-000000000001", ex.metadata.clientRecordId)
        assertEquals(listOf(95L, 120L), hr.samples.map { it.beatsPerMinute }) // sample before start dropped
    }

    @Test
    fun noHeartRateMeansSessionOnly() {
        assertEquals(1, session(emptyList()).toHealthConnect().size)
    }
}
