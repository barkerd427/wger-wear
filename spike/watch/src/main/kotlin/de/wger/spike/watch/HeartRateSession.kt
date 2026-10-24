package de.wger.spike.watch

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.guava.await
import java.time.Instant

/**
 * Health Services exercise session instead of the base repo's raw SensorManager listener: the
 * system owns the sensor, batches while the screen is off, and keeps the session alive across
 * process death. Samples (not just the average) are kept so the phone can write HeartRateRecord.
 */
class HeartRateSession(context: Context, private val onSample: (Instant, Double) -> Unit) {
    private val client = HealthServices.getClient(context).exerciseClient

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val boot = update.getUpdateDurationFromBoot()?.let { Instant.now().minus(it) } ?: return
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).forEach {
                onSample(it.getTimeInstant(boot), it.value)
            }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    suspend fun start() {
        val caps = client.getCapabilitiesAsync().await()
        val supported = caps.getExerciseTypeCapabilities(ExerciseType.WEIGHTLIFTING).supportedDataTypes
        client.setUpdateCallback(callback)
        client.startExerciseAsync(
            ExerciseConfig.builder(ExerciseType.WEIGHTLIFTING)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM).intersect(supported))
                .setIsAutoPauseAndResumeEnabled(false)
                .build(),
        ).await()
    }

    suspend fun end() {
        client.endExerciseAsync().await()
        client.clearUpdateCallbackAsync(callback).await()
    }
}
