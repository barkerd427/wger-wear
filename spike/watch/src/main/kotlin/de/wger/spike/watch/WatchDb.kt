package de.wger.spike.watch

import android.content.Context
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import de.wger.spike.sync.WgerConnector
import de.wger.spike.sync.wgerGymSchema
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * One PowerSync DB per process. Sync is connected only while a workout runs (from the foreground
 * service) or briefly from WorkManager, never permanently: the open HTTP stream has a ~20 s keepalive.
 */
class WatchDb(context: Context, serverUrl: String, accessToken: suspend () -> String) {
    val db: PowerSyncDatabase = PowerSyncDatabase(
        factory = DatabaseDriverFactory(context),
        schema = wgerGymSchema,
        dbFilename = "wger-watch.db",
    )
    private val connector = WgerConnector(serverUrl, HttpClient(OkHttp), accessToken)

    suspend fun startSync() = db.connect(connector)

    suspend fun stopSync() = db.disconnect()
}
