package de.wger.spike.sync

import com.powersync.PowerSyncDatabase
import com.powersync.inMemory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Runs a real (in-memory, native) PowerSync database on the JVM; the wger server is a Ktor MockEngine. */
class WgerConnectorTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun offlineWritesQueueAndUploadInWgerShape() = runBlocking {
        val db = PowerSyncDatabase.inMemory(wgerGymSchema, this)
        val start = Instant.parse("2026-10-20T17:00:00Z")
        val session = db.startSession(routineId = 7, dayId = 21, start = start)
        db.logSet(session, SetLog(exerciseId = 73, routineId = 7, slotEntryId = 300, iteration = 2,
            reps = 8.0, weightKg = 60.0, rir = 2.0, at = start.plusSeconds(300)))
        db.finishSession(session, start.plusSeconds(3600))

        val sent = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/api/v2/upload-powersync-data" -> {
                    assertEquals("Bearer at-123", req.headers["Authorization"])
                    sent += req.method to (req.body as TextContent).text
                    respond("""{"id":"x"}""", HttpStatusCode.OK, json)
                }
                else -> error("unexpected ${req.url}")
            }
        }
        val connector = WgerConnector("https://wger.example", HttpClient(engine), { "at-123" })
        // Each execute() is its own transaction: session insert, log insert, session update
        repeat(3) { connector.uploadData(db) }

        assertEquals(listOf(HttpMethod.Put, HttpMethod.Put, HttpMethod.Patch), sent.map { it.first })
        val log = Json.parseToJsonElement(sent[1].second).jsonObject
        assertEquals("manager_workoutlog", log["table"]!!.jsonPrimitive.content)
        val data = log["data"]!!.jsonObject
        assertEquals(session, data["session"]!!.jsonPrimitive.content) // session_id -> session
        assertEquals("73", data["exercise"]!!.jsonPrimitive.content)
        assertEquals("300", data["slot_entry"]!!.jsonPrimitive.content)
        assertEquals("2", data["iteration"]!!.jsonPrimitive.content)
        assertEquals("2.0", data["rir"]!!.jsonPrimitive.content)
        val sess = Json.parseToJsonElement(sent[0].second).jsonObject["data"]!!.jsonObject
        assertEquals("21", sess["day"]!!.jsonPrimitive.content) // what need_logs_to_advance keys on
        assertNull("queue drained", db.getNextCrudTransaction())
        db.close()
    }

    @Test
    fun transientFailureKeepsTransactionQueued() = runBlocking {
        val db = PowerSyncDatabase.inMemory(wgerGymSchema, this)
        db.startSession(7, 21, Instant.parse("2026-10-20T17:00:00Z"))
        val connector = WgerConnector(
            "https://wger.example",
            HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }),
            { "at" },
        )
        assertTrue(runCatching { connector.uploadData(db) }.isFailure)
        assertTrue("still queued", db.getNextCrudTransaction() != null)
        db.close()
    }

    @Test
    fun permanentRejectionIsReportedAndCompleted() = runBlocking {
        val db = PowerSyncDatabase.inMemory(wgerGymSchema, this)
        db.startSession(7, 21, Instant.parse("2026-10-20T17:00:00Z"))
        val rejected = mutableListOf<String>()
        val connector = WgerConnector(
            "https://wger.example",
            HttpClient(MockEngine { respond("""{"error":"Routine not found"}""", HttpStatusCode.OK, json) }),
            { "at" },
            onRejected = { t, _, e -> rejected += "$t:$e" },
        )
        connector.uploadData(db)
        assertEquals(1, rejected.size)
        assertNull(db.getNextCrudTransaction())
        db.close()
    }

    @Test
    fun credentialsComeFromWgerTokenEndpoint() = runBlocking {
        val engine = MockEngine { req ->
            assertEquals("/api/v2/powersync-token", req.url.encodedPath)
            respond("""{"token":"eyJ.jwt","powersync_url":"https://wger.example/ps/"}""", HttpStatusCode.OK, json)
        }
        val creds = WgerConnector("https://wger.example", HttpClient(engine), { "at" }).fetchCredentials()
        assertEquals("https://wger.example/ps/", creds.endpoint)
        assertEquals("eyJ.jwt", creds.token)
    }

    @Test
    fun uuid7IsTimeOrderedVersion7() {
        val a = uuid7(1_000)
        val b = uuid7(2_000)
        assertEquals('7', a[14])
        assertTrue(a < b)
    }
}
