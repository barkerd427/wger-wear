# PowerSync Kotlin SDK: feasibility spike for a Wear OS watch app

Research date: 2026-10-24. Sources: Maven Central artifacts I downloaded and unpacked (.module, .pom, .aar files), the GitHub repo at HEAD `5049e30` (2026-10-22), and docs.powersync.com.

## 1. Latest version and what it requires on Android

- **Latest stable release:** `com.powersync:core` **1.15.2** (maven-metadata `<release>`, lastUpdated 2026-09-28). The repo's `main` branch is already at 1.16.0, which is unreleased.
  https://repo1.maven.org/maven2/com/powersync/core/maven-metadata.xml
- **Artifact layout:** `core` is a KMP umbrella. On Android it resolves to `core-android`, a 4.7 KB stub, which pulls in `common-android`, a 957 KB AAR that holds all the logic.
- **minSdk:** 24 in the AAR manifests. Our minSdk of 30 is fine.
- **minCompileSdk:** **37** (from `META-INF/com/android/build/gradle/aar-metadata.properties`). The repo builds with compileSdk and targetSdk 37.
- **Kotlin:** the 1.15.2 module requires `kotlin-stdlib` **2.4.20**, and the class files carry Kotlin metadata `mv=[2,4,0]`. The repo uses Kotlin 2.4.21, AGP 9.3.2 and Gradle 9.8.1, and publishes with Gradle 9.7.1.
- **Bytecode:** Java 17 (class major version 61, `jvmTarget` JVM_17 on Android).
- **Transitive dependencies of 1.15.2 on Android (runtime):**
  - Ktor 3.5.2: `ktor-client-core`, `ktor-client-okhttp`, content-negotiation, `kotlinx-json`
  - `io.rsocket.kotlin` 0.20.0 (only used when the HTTP engine lacks backpressure; OkHttp does not need it)
  - `kotlinx-coroutines` 1.11.0, `kotlinx-datetime` 0.8.0, `kotlinx-io` 0.9.1
  - `co.touchlab:kermit` 2.2.0, `stately-concurrency` 2.1.0
  - **SQLite:** `androidx.sqlite:sqlite`, `sqlite-async` and `sqlite-bundled` 2.7.1, plus `com.powersync:powersync-sqlite-core` 0.5.3 (the Rust core extension)
- **Native libraries:** all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) are shipped, 16 KB-aligned since 1.3.1.

  | Library | arm64-v8a | armeabi-v7a |
  |---|---|---|
  | `libpowersync.so` (powersync-sqlite-core 0.5.3) | 417 KB | 280 KB |
  | `libsqliteJni.so` (androidx sqlite-bundled 2.7.1, a full SQLite build) | 1.28 MB | 1.25 MB |
  | **Total per ABI, uncompressed** | **≈1.7 MB** | **≈1.5 MB** |

  With dex for common, Ktor, rsocket, the kotlinx libraries and coroutines on top, expect roughly **+3–5 MB** of APK per ABI before R8. 1.15.1 fixed an R8 crash in CRUD upload (#377).
- **Room integration:** `com.powersync:integration-room:1.15.2` (beta).
  - Since **1.14.0** it targets **Room 3** (`androidx.room3:room3-runtime` 3.0.3). That is a different package from our `androidx.room` 2.6.1.
  - 1.6.0–1.13.0 targeted Room 2.8.4 with sqlite-bundled 2.6.2.
  - Room has to use `BundledSQLiteDriver` with `.loadPowerSyncExtension()`. You then wrap it with `RoomConnectionPool(roomDb, schema)` and call `PowerSyncDatabase.opened(...)`.
  - Synced tables must be declared as PowerSync **raw tables** (`RawTable`/`RawTableSchema` with put/delete statements). Room owns the schema.
  - Open issue #355: "Pre-sync data not applied to new tables after upgrade".
  - Source: `integrations/room/src/commonMain/.../RoomConnectionPool.kt`. Docs: https://docs.powersync.com/client-sdk-references/kotlin-multiplatform/libraries/room

## 2. Connector API (1.15.2)

```kotlin
abstract class PowerSyncBackendConnector {
  abstract suspend fun fetchCredentials(): PowerSyncCredentials?
  abstract suspend fun uploadData(database: PowerSyncDatabase)
  // plus helpers: getCredentialsCached(), invalidateCredentials(), prefetchCredentials()
}
data class PowerSyncCredentials(val endpoint: String, val token: String, val userId: String? = null)
```

**Opening, connecting and disconnecting**
- `PowerSyncDatabase(factory, schema, dbFilename)`, or `PowerSyncDatabase.opened(pool, ...)` for Room.
- `connect(connector, options = SyncOptions(crudThrottle, retryDelay, params, appMetadata, includeDefaultStreams = true, checkpointMode))`.
- `disconnect()` and `disconnectAndClear()`.

**CRUD upload queue (`ps_crud`)**
- `getCrudBatch(limit = 100): CrudBatch?` and `getNextCrudTransaction(): CrudTransaction?`.
- `CrudBatch(crud: List<CrudEntry>, hasMore, complete: suspend (writeCheckpoint: String?) -> Unit)`.
- `CrudEntry` fields: `id`, `clientId`, `op` (`UpdateType` PUT/PATCH/DELETE), `table`, `transactionId`, `metadata`, `opData`, `previousValues`.
- `uploadData` must apply the entries to the backend itself (here: a wger REST call or our own API), then call `complete()`.
- An exception thrown from `uploadData` triggers a retry after `retryDelay`. 1.15.2 fixed uploads not being retried.

**Sync rules and streams**
- These live server-side in the service config.
- With legacy sync rules, buckets download automatically, parameterised by JWT claims plus `SyncOptions.params`.
- With Sync Streams (stable since 1.11.0), streams marked auto-subscribe sync by default (`includeDefaultStreams`). Others are subscribed from the client: `db.syncStream("name", mapOf("p" to JsonParam.String(..))).subscribe(ttl, priority)`, which returns a `SyncStreamSubscription` with `waitForFirstSync()` and `unsubscribe()`.
- Status is exposed as `db.currentStatus` and `SyncStatus.syncStreams`.

**JWT: yes, the client needs one**
- `token` is a JWT that the PowerSync service validates against `client_auth` in `service.yaml` (`jwks_uri`, or static `jwks.keys`, plus `audience`).
- Required: a `kid` header; `sub`, `aud`, `iat` and `exp` claims; lifetime ≤ 24 h (≤ 60 min recommended).
- Algorithms: RS/ES/EdDSA. HS256 (`kty: oct`) is allowed but documented as dev-only. https://docs.powersync.com/configuration/auth/custom.md
- Plain wger DRF tokens do not qualify. We need a small token-minting endpoint, for example on the phone relay or a sidecar.
- **Unreleased 1.16.0** adds `connect(endpoint, Authenticator, MutationUploader?)` for download-only or upload-only modes. It also adds `connect(uploader)`, an upload-only mode that does not contact a PowerSync service at all.

## 3. Wear OS concerns

- **Connection model:** while connected, the client keeps **one long-lived streaming HTTP request** open.
  - On Android it uses OkHttp with the BSON or NDJSON stream. WebSocket/RSocket is only used for engines without backpressure, and the experimental websocket mode was removed in 1.3.1.
  - The service sends a keepalive about every 20 s, and the client times out after 40 s (`StreamingSyncClient.SOCKET_TIMEOUT`). This is a constant radio wake, bad for watch battery if left connected.
- **On-demand sync is supported:** `connect()` and `disconnect()` are cheap and resumable. The SDK picks up where it left off.
  - The official guidance (`demos/supabase-todolist/docs/BackgroundSync.md`) is either a `dataSync` foreground service or short WorkManager runs that connect, wait, then disconnect. Only one `PowerSyncDatabase` instance per process; no multi-process use.
  - On Wear, connect only during an active session or a charging/Wi-Fi WorkManager window.
  - `requestCheckpoint()` (experimental, service ≥ 1.24.0) plus `waitForFirstSync()` let a short job end deterministically.
- **Offline:** full offline-first. Local writes go to SQLite and are queued in `ps_crud` until `uploadData` succeeds. Reads work with no connection.
- **Memory:** the default SQLite cache was reduced to 50 MB in 1.11.2 (it had wrongly been 200 MB). It is a per-connection pool cap, not a reservation, but consider tuning it on the watch.
- **GitHub issues:** no Wear OS–specific reports found. Relevant ones:
  - #314 (open): `UnsatisfiedLinkError libsqliteJni.so`, attributed to an androidx bundled-driver bug (Google issue 442032108).
  - #177 (closed): wrong token used in background.
  - #340 (closed): `uploadData` not retried.
  - Open issue list: https://github.com/powersync-ja/powersync-kotlin/issues

## 4. Compatibility with our toolchain (Kotlin 2.0.21, AGP 8.7.2, Gradle 8.10.2, compileSdk 35)

**1.15.2 will not compile in our project.** A Kotlin compiler can read metadata at most one minor version newer than itself. Kotlin 2.0.21 reads up to 2.1, but 1.15.x ships 2.4 metadata. It also needs compileSdk 37, and the transitive stdlib 2.4.20 would be forced in.

| PowerSync version | Kotlin metadata | minCompileSdk | Minimum Kotlin in our project |
|---|---|---|---|
| 1.0.0 – 1.2.2 | 2.1 | 1 | **2.0.x**, so these compile with 2.0.21 (very old, early 2025; no raw tables, no Sync Streams, no Room integration) |
| 1.3.0 – 1.9.0 | 2.2 | 1 | 2.1.x (in practice 2.2.x, because stdlib 2.2 is pulled in) |
| 1.10.0 – 1.14.1 | 2.3 | 36 | 2.2.x (recommended 2.3.x); compileSdk 36 means AGP ≥ 8.9-ish |
| **1.15.0 – 1.15.2** | 2.4 | **37** | **2.3.0 minimum, 2.4.x recommended**; compileSdk 37 needs a recent AGP (the SDK itself builds with AGP 9.3.2) |

Other forced upgrades:
- Ktor 3.0.1 → 3.5.2 (it is on the `:mobile` classpath too, if shared).
- Coroutines 1.9.0 → 1.11.0.
- Room integration means Room 3. Our watch uses Room 2.6.1 with an AutoMigration history, so this is a major migration risk given the "never wipe the watch DB" rule.

Realistic options:
- **(a)** Bump the toolchain to Kotlin 2.4.x, KSP2, AGP 9.x, Gradle 9.x and compileSdk 37, then use 1.15.2.
- **(b)** Pin 1.9.0 (Kotlin 2.2, any compileSdk, Room 2.8 integration available from 1.6.0). That still requires Kotlin ≥ 2.1, really 2.2.

## 5. Flutter phone app and Kotlin watch app on one backend

**Yes.** Sync rules and streams plus auth live in the self-hosted PowerSync service. Each client SDK defines its own local `Schema` (tables and columns) independently, and may declare only a subset of the synced tables.

- Both apps need JWTs accepted by the same `client_auth` config (same `aud`), and both upload through their own connector to the same backend write path.
- Dart `powersync` latest is **2.5.0** (pub.dev, 2026-10-19).
- Both SDKs use the same Rust `powersync-sqlite-core` sync protocol.
- Keep column names and types consistent across the two schemas.

## Recommended Gradle coordinates

```kotlin
// Requires Kotlin >= 2.3 (2.4.x recommended), compileSdk 37
implementation("com.powersync:core:1.15.2")
// Optional, beta, requires Room 3 (androidx.room3:room3-runtime 3.0.3) + BundledSQLiteDriver
implementation("com.powersync:integration-room:1.15.2")
// Optional: Compose helpers (composeSyncStream, status)
implementation("com.powersync:compose:1.15.2")

// Fallback if the toolchain can only go to Kotlin 2.2 / compileSdk 35:
// implementation("com.powersync:core:1.9.0")
// implementation("com.powersync:integration-room:1.9.0")   // Room 2.8.4
```

To ship only watch ABIs, use `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }`, or rely on AAB ABI splits.
