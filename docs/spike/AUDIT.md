# Spike A — base audit of `wger-wear` (fork of Alvarinto/wger-wearOS-integration)

Audited at upstream commit `80ef95a` (merge of PR #5). ~3 000 lines of Kotlin/XML/Python, two Gradle
modules. Spanish-language codebase and docs.

## What it is

A **phone relay + offline watch** design:

```
wger REST v2 (static Token) ⇄ :mobile (Ktor) ⇄ Wear Data Layer (DataClient) ⇄ :wear (Room + Wear Compose M3)
```

The watch never talks to the server. The phone holds a URL + API token compiled into `BuildConfig`
from `local.properties`.

| Area | File(s) | State |
|---|---|---|
| Watch DB | `wear/.../data/local/{Entities,Daos,AppDatabase}.kt` | Room v2, exported schemas, AutoMigration 1→2, migration tests. Good discipline. |
| Workout state | `wear/.../data/WorkoutRepository.kt` | Room is the source of truth for the in-progress workout; survives process death. Linear: set N = slot N, no skipping/reordering. |
| HR | `wear/.../service/WorkoutTrackingService.kt` | `SensorManager` `TYPE_HEART_RATE`, foreground `health` service + `OngoingActivity`. **Only the average** is kept, in memory; samples are discarded. `health-services-client` is declared but unused. |
| Watch → phone | `wear/.../datalayer/WearSyncManager.kt` | `DataClient` + `setUrgent()`; resend of PENDING sessions on app open. |
| Phone → server | `mobile/.../datalayer/SessionUploader.kt`, `network/WgerApiClient.kt` | Creates `workoutsession`, then one `workoutlog` per set. Idempotent via SharedPreferences keys. ACK only when everything uploaded. |
| Routine fetch | `WgerApiClient.getRoutineDateSequenceGym` | Takes the **first day that has slots** from `date-sequence-gym`, not today's. Exercise name parsed from the slot `comment` (hack — the import script writes names there). Has a speculative "flattened structure" fallback that matches no real wger response. |
| Phone UI | `mobile/.../presentation/MainActivity.kt` | Debug panel (test connection / fetch / send). Not a product UI. |
| Tests | `wear/src/sharedTest`, `mobile/src/test` | Robolectric Room/migration/repository tests; Ktor `MockEngine` API tests. |

## Fit against our requirements

| Requirement | Base repo | Gap |
|---|---|---|
| Today's workout, `need_logs_to_advance` | First day with slots; ignores iteration/date | Must use the server's current-day logic and send `routine`/`iteration`/`date` with logs (see REPORT §4). |
| Log reps, weight, **RIR**, rest timer | reps + weight; rest timer with vibration ✅ | No RIR, no weight/rep units, no skip/swap. Small. |
| Live HR via **Health Services exercise session** | Raw `SensorManager` | Replace with `ExerciseClient` (also gives calories, exercise state, auto-pause, battery-optimised batching). Keep samples, not just the average. |
| Phone ⇄ watch interchangeable mid-session | No. Watch holds a one-way routine snapshot; phone has no workout UI; the server is only written, never read back | This is the core architectural gap. Needs a shared, server-backed local store on both devices (PowerSync) or a REST pull + merge. |
| Server = source of truth | Server is write-only from the client's view | Same. |
| Remote auth via OIDC, no passwords on watch | Static API token in phone `BuildConfig` | Replace entirely. |
| Health Connect (ExerciseSession + HeartRate) | None | Needs HR samples to be retained first. |
| Same package/signing as wger app (long term) | `applicationId = com.wger.companion` on both modules | Upstream wger is `de.wger.flutter`; the watch APK must use that id and the wger release key for Data Layer and Play multi-APK. Interim: our own id + key on both. |

## Code-level issues worth knowing

1. **Listener services cut work short.** `WearDataLayerListenerService`/`MobileDataLayerListenerService`
   launch coroutines in a scope cancelled in `onDestroy()`; Android can destroy the service as soon as
   `onDataChanged` returns, so a slow upload can be killed mid-way. Mitigated by idempotency +
   resend, but the right fix is `WorkManager` (or `goAsync`-style blocking in the callback).
2. **ACK race on reinstall.** ACK path `/wger/session_synced/{localId}` uses Room's autoincrement id;
   after a watch reinstall a stale ACK DataItem for id N could mark a *new* session N as SYNCED.
   (Uploader keys include `startTimestamp`, the ACK does not.)
3. **HR average in memory** (`heartRateReadings`), lost on process death; no samples → no
   `HeartRateRecord` series possible.
4. **`targetSdk = 34`, Kotlin 2.0.21, AGP 8.7.2, Room 2.6.1.** Fine to build today, but Play's target-API
   floor has moved on; plan to bump (and Room 2.7+/KSP2) before anything ships.
5. **No release signing**; Data Layer only works because both debug APKs share `~/.android/debug.keystore`.
6. Exercise names come from slot comments rather than `exerciseinfo`/translations; anything not created by
   `import_routine.py` shows "Ejercicio #n". `import_routine.py` silently maps unknown exercises to ID 1551.
7. Weight is always posted as kg with no `weight_unit`; no `rir`.

## Verdict on the base

Good **reference** for Wear-specific plumbing (foreground health service + `OngoingActivity`, Room
migration discipline, Data Layer urgency/retry gotchas, Robolectric test setup), and the phone-relay
upload path is solid for what it does. It is **not** a base to grow into our product: the data model
is a one-way flattened snapshot, auth is a baked-in token, HR is not via Health Services, and there is
no read path from the server. Recommendation: start a fresh project, port the Room/test/service
patterns and the Data Layer lessons (AGENTS.md "rules that can't be broken" are all valid), and keep
this repo as a reference. The licence (Apache-2.0) allows copying into an AGPL-3.0 wger codebase.
