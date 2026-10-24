package de.wger.spike.sync

import com.powersync.PowerSyncDatabase
import com.powersync.connectors.PowerSyncBackendConnector
import com.powersync.connectors.PowerSyncCredentials
import com.powersync.db.crud.UpdateType
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin port of wger-project/flutter lib/powersync/connector.dart against wger 2.7:
 *  - credentials: GET /api/v2/powersync-token (any authenticated credential) -> {token, powersync_url}
 *  - uploads:     one PUT/PATCH/DELETE per op to /api/v2/upload-powersync-data, body {table, data}
 *    200 + {"error": ...} = permanent refusal (complete anyway); non-2xx = throw, PowerSync retries.
 *
 * [accessToken] is whatever the app holds: an OIDC access token (allauth.idp.oidc, scope api:write)
 * or the allauth-headless JWT. Both are accepted by DRF on wger 2.7.
 */
class WgerConnector(
    private val serverUrl: String,
    private val http: HttpClient,
    private val accessToken: suspend () -> String,
    private val onRejected: (table: String, id: String, error: String) -> Unit = { _, _, _ -> },
) : PowerSyncBackendConnector() {

    override suspend fun fetchCredentials(): PowerSyncCredentials {
        val res = http.get("$serverUrl/api/v2/powersync-token") { bearerAuth(accessToken()) }
        check(res.status.isSuccess()) { "powersync-token: HTTP ${res.status.value}" }
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        return PowerSyncCredentials(
            endpoint = body.getValue("powersync_url").jsonPrimitive.content,
            token = body.getValue("token").jsonPrimitive.content,
        )
    }

    override suspend fun uploadData(database: PowerSyncDatabase) {
        val tx = database.getNextCrudTransaction() ?: return
        for (op in tx.crud) {
            val record = uploadRecord(op.table, op.id, op.opData?.jsonValues)
            val method = when (op.op) {
                UpdateType.PUT -> HttpMethod.Put
                UpdateType.PATCH -> HttpMethod.Patch
                UpdateType.DELETE -> HttpMethod.Delete
            }
            val res = http.request("$serverUrl/api/v2/upload-powersync-data") {
                this.method = method
                bearerAuth(accessToken())
                contentType(ContentType.Application.Json)
                setBody(record.toString())
            }
            // Throwing leaves the transaction queued; PowerSync retries with backoff.
            check(res.status.isSuccess()) { "upload ${op.table}/${op.id}: HTTP ${res.status.value}" }
            val error = runCatching { Json.parseToJsonElement(res.bodyAsText()).jsonObject["error"] }.getOrNull()
            if (error != null) onRejected(op.table, op.id, error.toString())
        }
        tx.complete(null)
    }

    companion object {
        /** Same transform as the Flutter app: `foo_id` -> `foo` (Django FK names), keep `external_id`. */
        fun uploadRecord(table: String, id: String, data: JsonObject?): JsonObject = buildJsonObject {
            put("table", JsonPrimitive(table))
            put(
                "data",
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    data?.forEach { (k, v): Map.Entry<String, JsonElement> ->
                        if (k == "id") return@forEach
                        put(if (k.endsWith("_id") && k != "external_id") k.removeSuffix("_id") else k, v)
                    }
                },
            )
        }
    }
}
