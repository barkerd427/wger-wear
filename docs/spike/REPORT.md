# Spike A: wger Wear OS gym app. Base audit, PowerSync and Health Connect feasibility

> **Branch:** `spike/wear-feasibility`, committed locally. **Pushing failed**: GitHub returned 403 for
> every branch name, including the session's `claude/wger-wear-os-spike-6dmeo3`. The Claude GitHub
> App has no write access to `barkerd427/wger-wear`. The commits exist only in the cloud session
> until someone fixes that access and pushes.
>
> **Date:** 2026-10-24. **Targets:** wger server tag `2.7` (`65a1d40`) and wger-project/flutter master
> `5dd00d6` (app 2.1.1). Both were read, not run: there was no wger server and no watch in the VM.
>
> **Status:** throwaway feasibility code. Nothing under `spike/` is product code.

## TL;DR

| Question | Answer | Confidence |
|---|---|---|
| Is the base repo a usable foundation? | **No, but it is useful as a reference.** It builds and its 18 tests pass. Its design is a one-way relay with a token compiled into the app, and the server is never read back. Start fresh and port its Wear plumbing patterns. See [AUDIT.md](AUDIT.md). | High |
| Can PowerSync be the sync layer? | **Yes, with one gap.** wger 2.7 syncs `manager_routine`, `manager_workoutsession` and `manager_workoutlog` per user, and accepts uploads for sessions and logs. The routine *structure* (days, slots, set configs) is **not** synced, so it has to come over REST and be cached on the device. A Kotlin connector for wger's real endpoints works against a real in-memory PowerSync DB: 5/5 JVM tests pass. | High (code); medium (live service never exercised) |
| Does the PowerSync Kotlin SDK fit a Wear OS app? | **Yes**, on the wger app's own toolchain: AGP 9.2.1, Gradle 9.6.1, Kotlin 2.3.20, compileSdk 37. It **does not** fit the base repo's toolchain (Kotlin 2.0.21, compileSdk 35). Native libs add about 1.7 MB per ABI. Keep sync connected only during a workout. | High (compiles); unknown (battery on device) |
| Remote auth via OIDC, no password on the watch? | **Yes.** wger 2.7 ships an OIDC provider (`allauth.idp.oidc`) with PKCE, public clients, `api:read`/`api:write` scopes and a device-code endpoint. `RemoteAuthClient` with PKCE compiles against it. It needs one-time server-admin setup: an OIDC key plus a client with redirect `https://wear.googleapis.com/3p_auth/<package>`. | Medium-high (not run end to end) |
| Today's workout and `need_logs_to_advance`? | **Server-computed.** Use `date-sequence-gym` and pick today's entry, as the Flutter app does. A day advances when a **session with `day` set** exists on the previous local date. Logs should still carry `routine`, `iteration` and `slot_entry` so progression rules apply. | High (read in code) |
| Phone and watch interchangeable mid-session? | **Feasible if both devices are PowerSync clients** of the same server. They then share session and log rows through the server. Today the Flutter app writes logs through PowerSync but reads routine structure over REST into memory only. | Medium |
| Health Connect? | **Feasible on the phone, not the watch.** The mapping to `ExerciseSessionRecord` + `HeartRateRecord` compiles and its 2 tests pass. Using `clientRecordId` = wger session UUID makes writes idempotent. The watch must keep **HR samples**, not just an average, and they must reach the phone. wger 2.7 has no field for HR samples on a workout session. | High (mapping); open (how samples reach the phone) |

**Recommendation:** go native Kotlin with PowerSync on **both** devices. Use REST for routine structure,
cached locally. Give the watch its own OIDC PKCE grant through `RemoteAuthClient`. Use Health Services
`ExerciseClient` for HR, store the samples locally, and have the **phone** write Health Connect.
Build on the wger app's toolchain from day one. Details and open risks are below.

---

## 1. Base audit

The full audit is in [AUDIT.md](AUDIT.md).

- **Build:** `assembleDebug`, `:wear:testDebugUnitTest` and `:mobile:testDebugUnitTest` all pass
  (18/18) on JDK 21.
- **Reusable:**
  - Room migration discipline and tests.
  - Foreground `health` service + `OngoingActivity`.
  - Data Layer rules: `setUrgent`, `DataClient` over `MessageClient`, and a payload that changes on
    every resend.
  - Robolectric/MockEngine test setup.
- **Not reusable:**
  - The flattened one-way routine snapshot.
  - The static API token in phone `BuildConfig`. wger 2.7's Flutter app has dropped permanent-token
    login altogether.
  - `SensorManager` HR that keeps only an in-memory average.
  - "First day with slots" in place of today's day.
  - No RIR or units on logs.
  - The `com.wger.companion` package.
- Apache-2.0, so code can be copied into the AGPL-3.0 wger repos.

## 2. PowerSync feasibility

### What wger 2.7 actually provides
(Citations in [research/wger-server.md](research/wger-server.md). I re-checked the key ones myself.)

- **Endpoints:**
  - `GET /api/v2/powersync-token` → `{token, powersync_url}` (`wger/core/api/views.py:365`). The
    token is an RS256 JWT with `sub=user_id`, `aud=powersync`, `kid`, and a lifetime of
    **600 s by default** (`POWERSYNC_TOKEN_LIFETIME`). Any authenticated credential can call it,
    including OIDC access tokens (`wger/utils/oidc_auth.py:67`, `settings_global.py:522-532`).
  - `PUT|PATCH|DELETE /api/v2/upload-powersync-data`, one op per request with body `{table, data}`:
    - A **200 with `{"error":…}`** is a permanent refusal. Non-2xx responses are retried.
    - A repeated create with the same id is a no-op success (`wger/utils/powersync.py:153`), so
      at-least-once delivery is safe.
- **Publication** (core migration 0027): the manager tables are **only** `manager_routine`,
  `manager_workoutsession` and `manager_workoutlog`. Day, slot, slot_entry and the set configs are not
  synced.
- **Sync rules** live in `wger-project/docker`, not the server repo. They are Sync Streams
  (`edition: 3`, auto-subscribe) filtered per user by `auth.user_id()`.
- **Writes:** sessions and logs allow create, update and delete. Routines allow update and delete
  only; creation goes through REST. Every write checks foreign-key ownership and runs the REST
  serializer.
- **Ids:** session and log primary keys are UUIDv7 and the `id` is writable, so **the watch can mint
  ids offline.**

### What the spike proved (`spike/shared`)
- `WgerConnector` is a Kotlin port of the Flutter connector (`lib/powersync/connector.dart`): token
  fetch, one request per op, the `foo_id`→`foo` field transform, and the permanent-vs-transient
  rules.
- `wgerGymSchema` covers the three manager tables, with column names copied from the Flutter schema.
- `startSession`, `logSet` and `finishSession` write with UUIDv7 ids and set `day_id`, `iteration`,
  `slot_entry_id`, `rir` and units.
- `WgerConnectorTest` runs **a real native PowerSync DB in memory on the JVM**, with the wger
  server mocked by Ktor `MockEngine`. 5/5 pass:
  - Offline writes queue up and upload in exactly the wger payload shape: PUT session, PUT log,
    PATCH session. `session_id`→`session` and `day_id`→`day`. The queue drains afterwards.
  - A 503 keeps the transaction queued.
  - A 200 with `{error}` is reported and completed, so it doesn't block the queue.
  - Credentials parse correctly from `/api/v2/powersync-token`.
  - UUIDv7 ids have the right format and sort by time.

### Toolchain constraints (measured)
- **`com.powersync:core:1.15.2` (latest):**
  - The AARs declare **minCompileSdk 37**, and the library needs kotlin-stdlib 2.4.20.
  - Compiling with **Kotlin 2.3.20** works (verified): the compiler reads metadata one version ahead.
  - So the wger Flutter app's Android toolchain (AGP 9.2.1, Gradle 9.6.1, Kotlin 2.3.20) is enough.
    The base repo's toolchain (AGP 8.7.2, Kotlin 2.0.21, compileSdk 35) is **not**.
- It pulls in Ktor 3.5.2, coroutines 1.11, `androidx.sqlite` 2.7 bundled driver and rsocket.
- **Room:** `integration-room` 1.14+ needs **Room 3** (beta) plus raw tables.
  - Don't combine it with Room 2 in one database.
  - Recommendation: use PowerSync's own SQLite for synced data.
  - Use a small Room or DataStore store for local-only state, such as the in-progress UI step and the
    HR samples. Alternatively, keep those in PowerSync `localOnly` tables.
- **Size, measured on `:watch`:**
  - `libpowersync.so` + `libsqliteJni.so` are 1.70 MB uncompressed on arm64 and 1.54 MB on armeabi-v7a.
  - The R8 release APK with both ABIs is **3.6 MB**. That is a lower bound: the spike has no entry
    points for R8 to keep, so it is mostly the native libs.
  - Debug APK: 21.8 MB.
- **Fallback for older toolchains:** `core:1.9.0` with Kotlin ≥ 2.1, effectively 2.2, and Room 2.8
  integration. There is no reason to take it if we start on the wger toolchain.

### Wear-specific concerns (not measurable here)
- **Battery:** while connected, PowerSync holds one HTTP stream open, with a server keepalive
  about every 20 s and a client timeout at 40 s. Plan:
  - `connect()` when a workout starts, from the foreground service that already exists for HR.
  - `disconnect()` a little while after the workout ends.
  - A periodic `WorkManager` job (connect → wait for upload and checkpoint → disconnect) to pick up
    routine changes and push anything still queued.
  - **Open question:** measure the drain on a Pixel Watch.
- **Reachability:** the watch must reach both wger and the PowerSync service. Over Bluetooth it goes
  through the phone's proxy, otherwise over Wi-Fi or LTE.
  - **Open question:** whether a Tailscale-only server is reachable from the watch. The base repo
    relays through the phone for exactly this reason.
  - If the server is public HTTPS, a direct connection is fine.
  - If not, fall back to the base repo's phone-relay design: the watch holds a local PowerSync DB
    with no `connect()`, and the phone uploads the watch's CRUD queue. That is far more custom code.
- **Token refresh:** the PowerSync token lasts 600 s. Credentials have to be refetched during
  a workout over a phone-proxied link, and the SDK does this automatically through
  `fetchCredentials`. The OIDC access token lasts about 1 h and needs a refresh-token flow.

## 3. Auth: Wear OS remote auth against wger's OIDC provider

wger 2.7 (`settings_global.py:143,327-338`, `wger/utils/oidc_auth.py`):

- **Provider:** `allauth.idp.oidc`, enabled only when the self-hoster sets `IDP_OIDC_PRIVATE_KEY`
  (generated with `manage.py generate-oidc-key`).
- **Endpoints:**
  - Discovery: `/.well-known/openid-configuration`.
  - Authorize: `/identity/o/authorize`.
  - Token: `/identity/o/api/token`.
  - Device flow: `/identity/o/api/device/code` + `/identity/o/device`.
- **Rules and token lifetimes:**
  - PKCE S256 is enforced and public clients are allowed.
  - **No dynamic client registration:** an admin creates the client in Django admin.
  - Scopes are `api:read` and `api:write`, enforced by HTTP method on every DRF endpoint.
  - Access tokens are opaque and last about 1 h. Refresh tokens last about 120 days.

Watch side (`spike/watch/.../RemoteSignIn.kt`, compiles):

- **PKCE flow:** `RemoteAuthClient` + `OAuthRequest` with `CodeVerifier`/`CodeChallenge`. The
  browser opens on the paired phone. The redirect is `https://wear.googleapis.com/3p_auth/<applicationId>`,
  and the code is exchanged at `/identity/o/api/token`.
- **Admin setup per server:**
  1. Set `IDP_OIDC_PRIVATE_KEY`.
  2. Create a public client whose redirect URI is the `3p_auth` URL above, with scopes
     `openid api:read api:write`.
  - The client id has to be configurable in the app, or discoverable. That is UX work.
- **Google's guidance** ranks Credential Manager first, but it doesn't apply to a self-hosted IdP.
  OAuth PKCE and the device grant are its listed backups.
- **Data Layer token hand-off from the phone app** can only be an *additional* method under that
  guidance. It also doesn't fit wger: the Flutter app's refresh tokens rotate, so the phone can't
  share its own. The phone app would have to run its own OIDC grant for the watch. Skip it.
- **Device-code grant:** a fallback for watches without a paired Android phone. The endpoints exist
  in allauth, but wger's tests don't cover it. **Open question:** test it against a real 2.7 server.
- **Note:** the Flutter app's own login uses allauth headless with a username and password. It is not
  OIDC. Its `/user/app-auth/` browser hand-off redirects to a hard-coded `wger://app-auth` scheme
  that belongs to the Flutter app, so a watch can't reuse it.

## 4. Today's workout and `need_logs_to_advance`

- `GET /api/v2/routine/{id}/date-sequence-gym/` returns the per-date sequence. Pick the entry whose
  date is today, as the Flutter app does (`lib/models/workouts/routine.dart:193-236`).
- **Advance rule** (`wger/manager/models/routine.py:239-273`, re-checked):
  - A day with `need_logs_to_advance` holds the sequence until a **WorkoutSession with
    `day=<that day>`** exists whose start falls, in the user's timezone, on the date *before* the
    date being computed.
  - So **create the session with `day` set**. The spike's `startSession` does this, and the upload
    test asserts `day` in the payload.
  - The server can also fill `session.day` itself from a session-less log's `slot_entry`
    (`log.py:257-307`), but don't depend on that.
- Logs should carry the following so automatic progression works and the web UI shows targets:
  - `routine`, `iteration` and `slot_entry`.
  - The targets: `repetitions_target`, `weight_target`, `rir_target`.
  - Units: `repetitions_unit` 1 = reps, `weight_unit` 1 = kg / 2 = lb.
- The structure (days, slots, set configs, exercise names) must be **fetched over REST and cached on
  the watch for offline use**. Candidate endpoints are `date-sequence-gym` plus `routine/{id}/structure/`.
  - The Flutter app keeps this in memory only, so nothing exists to reuse.
  - Treat the numeric and label fields as nullable: master makes them nullable, and 2.7 can already
    return nulls.
- **2.7 data-loss risk:** deleting a routine or day on the server cascades to its sessions and logs.
  Master fixes this (migration 0030). Not our bug, but worth knowing.

## 5. Logging, rest timer, live HR

- **Set logging (reps, weight, RIR):** a direct mapping to the `manager_workoutlog` columns,
  demonstrated in `logSet`. A PowerSync write is a local SQLite insert, so it is instant and works
  offline.
- **Rest timer:** keep the base repo's approach, which derives the end time from the last set's
  timestamp plus `rest`, held in the DB, with vibration on expiry. wger has no rest-timer state on
  the server, so it doesn't need to sync.
- **HR** (`spike/watch/.../HeartRateSession.kt`, compiles):
  - Health Services `ExerciseClient` with `ExerciseType.WEIGHTLIFTING` and `HEART_RATE_BPM` (checked
    against capabilities first), plus an update callback that collects timestamped samples.
  - It replaces the base repo's `SensorManager` listener. It still needs the foreground `health`
    service and `OngoingActivity`; reuse the base repo's.
- **Permissions:** Wear OS 6 / API 36 replaces `BODY_SENSORS` with
  `android.permission.health.READ_HEART_RATE`. The spike manifest declares both, with
  `BODY_SENSORS` capped at `maxSdkVersion=35`. **Open question:** check exactly which permissions
  the Pixel Watch 5's OS version requests.

## 6. Phone and watch interchangeable mid-session

- If both apps are PowerSync clients of the same wger user, a session or log written on either
  device reaches the other after upload and the next sync checkpoint. Typical latency is seconds when
  both are online. Rows are keyed by UUID and created idempotently, so no merge logic is needed for
  inserts.
- **Gaps:**
  1. **The Flutter app is not fully PowerSync-driven.** Logs and sessions are in PowerSync, but gym
     mode reads routine structure over REST, and reuses an open session (< 5 h, same day, same
     routine) by querying. A watch-started session would show up on the phone if the phone queries
     PowerSync's `manager_workoutsession`. This needs checking in the Flutter gym-mode code, or a
     small upstream change.
  2. **Watch offline.** Writes queue up, and the phone only sees them once the watch reconnects.
     That's acceptable, but it isn't truly interchangeable while the watch is offline.
  3. **"Current step" state** (which set you are on) isn't a wger concept. Derive it from logs
     already written in the session against the day's slot list. Both devices can compute it the
     same way.
  4. **Concurrent edits** of the same log on both devices: last write wins, since PowerSync uploads
     PATCHes in order. Acceptable for gym use.

## 7. Health Connect

- **Health Connect runs on the phone.** I found no Wear OS availability. The plan of having the
  phone app write Health Connect is therefore the only option, not just the preferred one.
- **Mapping** (`spike/phone/.../HealthConnectMapper.kt`, 2/2 tests pass):
  - One `ExerciseSessionRecord` (`EXERCISE_TYPE_WEIGHTLIFTING`, title = routine name).
  - One `HeartRateRecord` with the samples inside the session window.
  - `Metadata.activelyRecorded(Device(TYPE_WATCH), clientRecordId = "wger-session-<uuid>")`.
    Health Connect upserts on `clientRecordId`, so retries and two writers never duplicate records.
  - Optional later: `ExerciseSegment`s per exercise. That needs a table from wger exercises to Health
    Connect segment types.
- **The hard part is getting HR samples to the phone.** wger 2.7 has no HR field on sessions.
  Measurements (migration 0006/0007) gained `source`, `external_id` and `heart_rate` /
  `resting_heart_rate` metric types, but those are scalar daily-style values. Options:
  - a. **Interim (our own apps):** the watch sends the finished session's HR samples to the phone
    over Data Layer `DataClient` (urgent, as in the base repo). The phone writes Health Connect.
    No server change.
  - b. **Upstream:** a wger table or field for per-session HR samples. It would sync through
    PowerSync, so the phone app can write Health Connect from server data, even for sessions logged
    on another phone. That is a server change to propose upstream.
  - c. Store only the average HR in the session `notes`, as the base repo does. Too lossy for
    `HeartRateRecord`.
  - Recommendation: (a) now, (b) as the upstream ask.
- Today the Flutter app only *reads* from Health Connect into measurements, through `health_bridge`.
  Writing exercise sessions needs `WRITE_EXERCISE` + `WRITE_HEART_RATE` and a Play Health Connect
  declaration.

## 8. Packaging and the upstream path

- **Long term:**
  - The watch APK must use **`applicationId de.wger.flutter`** and the wger release signing key
    (fastlane, GPG-encrypted in CI).
  - It ships on the same Play listing with the Wear form factor and a versionCode range that doesn't
    overlap the phone's.
  - The Flutter repo's `android/` could host a `:wear` Gradle module, since AGP 9.2.1 is already
    there.
  - The Flutter app has **no** Data Layer or Wear code today. Data Layer is only needed for option
    7a and is optional otherwise.
- **Interim:** our own id for both apps, e.g. `de.wger.spike`, signed with our key. With PowerSync on
  both devices the watch doesn't need the phone app for sync, only for remote auth (any phone browser
  works) and the HR hand-off (7a).
- **targetSdk:** the spike uses 36. Credential Manager on Wear asks for ≥ 35, and the Play floor
  keeps rising.

## 9. Risks and open questions (need a device and/or a live server)

1. Battery cost of PowerSync streaming on a Pixel Watch during a 60–90 min session, versus
   connecting only at start, end and periodically.
2. Whether the watch can reach a self-hosted server: Tailscale-only versus public HTTPS, and the
   behaviour through the phone's Bluetooth proxy. This decides direct PowerSync versus the
   phone-relay fallback.
3. The OIDC PKCE round trip through `RemoteAuthClient` against a real 2.7 server with
   `IDP_OIDC_PRIVATE_KEY` set. The device-code grant is untested upstream.
4. Whether the PowerSync service in `wger-project/docker` accepts the token audience and `kid` as
   deployed (`client_auth` JWKS from `/api/v2/powersync-keys`). The Flutter app depends on this, so
   it very likely works.
5. Whether the Flutter gym mode notices a session started on the watch, in which case the phone can
   continue it.
6. HR permissions and `ExerciseClient` capabilities for weightlifting on the Pixel Watch 5's OS
   version.
7. Upstream appetite for a per-session HR samples table (7b) and a `:wear` module in the Flutter repo.

## 10. Reproduce

Environment:
- JDK 21. JDK 17 is not present in this image.
- SDK packages: `platforms;android-35`, `android-37.0`, `build-tools;34.0.0`/`36.0.0`/`37.0.0`.
- Maven Central returned **429** from the cloud IP. The workaround is a user-level init script,
  `~/.gradle/init.d/mirror.gradle`, that puts
  `https://maven-central.storage-download.googleapis.com/maven2/` first. It is not in the repo.

```bash
# base repo (unchanged)
./gradlew -q assembleDebug :wear:testDebugUnitTest :mobile:testDebugUnitTest     # 18 tests
# throwaway spike build (separate Gradle build, wger-app toolchain)
cd spike && ./gradlew -q :shared:test :phone:testDebugUnitTest :watch:assembleDebug :phone:assembleDebug  # 5 + 2 tests
```

| Path | What |
|---|---|
| `docs/spike/AUDIT.md` | Base repo audit and build results |
| `docs/spike/research/*.md` | Agent research notes with file:line citations: wger server 2.7, Flutter app, PowerSync Kotlin SDK |
| `spike/shared` | PowerSync schema, wger connector, write helpers, JVM tests (Kotlin/JVM) |
| `spike/watch` | Wear app module: PowerSync DB on Android, Health Services HR, RemoteAuthClient PKCE (compile only) |
| `spike/phone` | Health Connect mapper and tests |

**Not done:** anything on a device or emulator (none available), a live wger or PowerSync server,
lint, and UI.
