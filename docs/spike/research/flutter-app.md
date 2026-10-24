# wger Flutter app (wger-project/flutter) — feasibility notes for a native Wear OS companion

Source: shallow clone at `/tmp/claude-0/up/flutter`, commit `5dd00d6c` (2026-10-23), `pubspec.yaml:24` `version: 2.1.1+280`.
All paths are relative to that clone. Read-only research.

---

## 1. PowerSync

### Setup
- Dependency `powersync: ^2.4.0` plus Drift on top (`drift`, `drift_sqlite_async`) — `pubspec.yaml:34,77,80`.
- DB instance: `lib/database/powersync/powersync.dart:87-167` (`powerSyncInstance` Riverpod provider, keepAlive). Calls `db.initialize()`, then creates raw tables itself (`:97`, `:188-237`).
- **File**: `_dbFilename = 'powersync-wger.db'` (`powersync.dart:45`) in `<getApplicationSupportDirectory()>/database/` (`dbDirectoryName = 'database'`, `:54`; path built `:311-333`). On Android that is `/data/data/de.wger.flutter/files/database/powersync-wger.db` (app_flutter support dir). Legacy files in the support-dir root are migrated (`:337-351`). Excluded from cloud backup: `android/app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` (`<exclude domain="file" path="database"/>`).
- Connect: `connectPowerSync()` (`powersync.dart:255-275`) builds a `DjangoConnector(baseUrl, ApiClient(baseUrl, client: authHttpClient))`. Connect/disconnect is driven by network-adapter availability (`:132-151`). Pending-upload counter reads `ps_crud` (`:289-294`).
- Drift wrapper over the same DB: `lib/database/powersync/database.dart` (`driftPowerSyncDatabase`), used by all repositories.

### Schema (`lib/powersync/schema.dart:30-79`)
PowerSync view tables:
- Core: language, license, user profile.
- Exercises: alias, comment, muscle, exercise↔muscle M2N, equipment, category, image, video; **raw tables** `exercises_exercise`, `exercises_translation` (`schema.dart:75-78`, DDL in `powersync.dart:188-218`, INTEGER ids).
- Nutrition: ingredient (+image, weight unit), nutritional plan, meal, meal item, log item.
- Measurements: category, entry.
- **Routines** (`schema.dart:62-67`): `manager_routine`, `manager_workoutlog`, `manager_workoutsession`, `core_repetitionunit`, `core_weightunit`.
- Gallery image.

Routine-domain columns (`lib/database/powersync/tables/routines.dart`):
- `manager_routine` (`:47-59`): name, description, created, start, end, is_template, is_public, fit_in_week. Int id. Templates are excluded server-side by sync rules (`routines_repository.dart:124-128`).
- `manager_workoutlog` (`:87-113`): exercise_id, routine_id, session_id (TEXT/UUID), iteration, slot_entry_id, rir, rir_target, repetitions, repetitions_target, repetitions_unit_id, weight, weight_target, weight_unit_id, date (TEXT ISO UTC). Id is TEXT, client-generated `ps.uuid.v7()` (`:66`).
- `manager_workoutsession` (`:142-159`): routine_id, day_id, notes, impression (`'1'|'2'|'3'`, `lib/features/routines/models/session.dart:31-47`), datetime_start, datetime_end, plus legacy read-only date/time_start/time_end. Id TEXT uuid v7 (`routines.dart:120`).
- **Not in PowerSync**: days, slots, slot entries, set configs (sets/weight/reps/rir/rest + max variants) and the computed date sequence.

### Connector (`lib/powersync/connector.dart`)
- `fetchCredentials()` (`:144-200`): `GET {server}/api/v2/powersync-token` (`lib/powersync/api_client.dart:46-56`) with the normal `Authorization: Bearer <access JWT>` (injected by `AuthHttpClient`). Response `{token, powersync_url}` (`connector.dart:165-167`). Endpoint chosen by `findLivePowerSyncUrl` (`lib/core/helpers.dart:150-180`): tries advertised `powersync_url`, then default `{server}/ps/` (`helpers.dart:128-138`), each probed at `<url>/probes/liveness`. Returns `PowerSyncCredentials(endpoint, token, userId = JWT sub, expiresAt = JWT exp)` (`connector.dart:194-199`). 30 s fetch timeout (`:110`).
- `uploadData()` (`:296-304`) → `processTransaction()` (`:315-372`): for each CRUD op, body
  ```json
  {"table": "<sqlite table name, e.g. manager_workoutlog>", "data": {"id": "<row id>", ...columns}}
  ```
  sent to the single endpoint `{server}/api/v2/upload-powersync-data` (`api_client.dart:32`) as **PUT** (put/upsert), **PATCH** (patch), **DELETE** (delete) (`api_client.dart:63-73`), `Content-Type: application/json`.
- Transform (`connector.dart:265-290`): injects `id`; strips `_id` suffix from FK columns (`exercise_id`→`exercise`, `session_id`→`session`, `routine_id`→`routine`, `slot_entry_id`→`slot_entry`, `weight_unit_id`→`weight_unit`…; `external_id` kept) (`:284`); date-only fields trimmed to `YYYY-MM-DD` for `manager_routine.start/end`, `nutrition_nutritionplan.start/end`, `gallery_image.date` (`:241-245`).
- Response contract (`:375-394`): 2xx = ok unless body is JSON with `error` key → permanent reject (surfaced once, transaction still completed); 5xx/408/429/401/403 → retry (throw, stays queued); other 4xx → reject. Retries re-send the whole transaction, so server handlers are idempotent (`:306-313` docstring).

### PowerSync vs REST
- **PowerSync**: top-level routine list/edit/delete, all workout sessions and logs (read + write), units, exercises catalogue, nutrition, measurements, gallery metadata.
- **REST still used** (`WgerBaseProvider`, `lib/core/network/base_provider.dart`):
  - Routine structure on demand: `GET /api/v2/routine/{id}/structure/`, `/date-sequence-display/`, `/date-sequence-gym/` (`lib/features/routines/providers/routines_repository.dart:58-61,79-114`); only held in memory, carried across stream emissions (`routines_notifier.dart:103-130`); offline gym mode fails if never fetched (`gym_mode.dart:91-93`, `routines_list.dart:62`).
  - Creating routines and all CRUD on day/slot/slot-entry/*-config endpoints (`routines_repository.dart:116-308`; class doc `:46-51`).
  - Exercise search/submission, ingredient search/barcode, trophies, profile, gallery uploads (multipart), account email (headless) — see `exercise_repository.dart:81`, `ingredient_repository.dart:179`, `trophy_repository.dart:49`, `account_repository.dart:45,57`, `gallery_repository.dart:65`.
- Server must be ≥ 2.7: `MIN_SERVER_VERSION = '2.7'` (`lib/core/consts.dart:41`), version gate in `lib/core/network/server_gating.dart:36-71`.

---

## 2. Auth

- **allauth headless "app" client**, base `/allauth/app/v1/` (`lib/core/network/headless_auth_api.dart:33-38`):
  - `POST auth/login` `{username, password}` (`:85-93`)
  - `POST auth/signup` `{username, password, email?}` (`:61-81`)
  - `POST auth/2fa/authenticate` `{code}` + header `X-Session-Token` (`:97-108`, `:42`); MFA detected from 401 + `meta.session_token` + pending `mfa_authenticate` flow (`:168-185`).
  - `POST tokens/refresh` `{refresh_token}` (`:113-132`). **Rotation is on**: the old refresh token is invalidated by each refresh (`:110-112`).
  - Tokens read from `meta` (login/signup) or `data` (refresh) (`:191-209`). Headers: JSON + Accept + UA `wger App/<ver> (<pkg>; build: N; platform: android)` (`lib/core/network/api_headers.dart:24-47`).
- Access token = JWT sent as `Authorization: Bearer <jwt>` (`lib/core/network/auth_credential.dart:24-41`); user id = JWT `sub` (`:51`).
- Other entry points (`lib/core/network/auth_notifier.dart`): `login()` with either username/password or a **pasted refresh token** minted on the website (`:126-157`, `_obtainCredentials` `:215-240`); `completeMfa` (`:158-186`); auto-login from storage (`:341-500`); single-flight refresh `refreshAccessToken()` (`:663-700+`, 15 s timeout `:56`).
- **Browser handoff (no OAuth/OIDC client id)**: app opens `{server}/user/app-auth/?state=<nonce>` (`lib/features/auth/widgets/auth_card.dart:111-125`); server redirects to `wger://app-auth#token=<refresh token>&state=...`; `AppLinkRouter` validates the 256-bit nonce (10 min TTL) and calls `login('', '', serverUrl, token)` (`lib/core/app_link_router.dart:31-145`). Covers social login/passkeys/SSO server-side (`web_handoff_link.dart` doc). Manifest registers scheme `wger`, host `app-auth` (`android/app/src/main/AndroidManifest.xml:86-93`); Flutter deep linking disabled (`:82-84`). No other OAuth client id / redirect URI.
- **Storage**: access token, expiry, server URL in SharedPreferences (`SharedPreferencesAsync`) keys `accessToken`, `serverUrl`, … (`lib/core/network/auth_credentials_storage.dart:54-98`, keys `lib/core/consts.dart:93-95`). Refresh token in `flutter_secure_storage` (EncryptedSharedPreferences on Android) key `wger_refresh_token` (`lib/core/network/secure_token_storage.dart:23-54`, `consts.dart:120`). Legacy permanent DRF `Token` auth was removed (`auth_credentials_storage.dart:147-150`).
- `AuthHttpClient` (`lib/core/network/auth_http_client.dart:30-80`): pre-emptive refresh 30 s before expiry, one retry on 401/`token_not_valid`, then session expired.
- **PowerSync JWT**: separate short-lived token from `GET /api/v2/powersync-token` authenticated with the access JWT (see §1). User switch wipes the local DB (`auth_notifier.dart:250-300`, DB-owner marker = JWT `sub`).

**Implications for the watch**: the phone's refresh token cannot be shared/copied to the watch — rotation means whichever device refreshes first invalidates the other's. A watch needs either its own allauth session (own refresh token, e.g. via a phone-assisted handoff) or must relay everything through the phone. DRF `Token` (what our current repo uses) is no longer the app's auth model, though the server still accepts it for REST.

---

## 3. Health integration (`health_bridge`)

- Dependency: git fork `ytsni/health_bridge` `v2.0.0` (fork of `health` 13.3.1) (`pubspec.yaml:51-57`).
- Direction: **read-only import from Health Connect / Apple Health into wger measurements**. No writes, no workouts/exercise sessions. Only `requestAuthorization(..., HealthDataAccess.READ)` (`lib/features/health/providers/health_repository.dart:160-175`); calls are `getHealthDataFromTypes`, `getHealthIntervalDataFromTypes`, `getAuthorizationSnapshot`, `requestHealthDataHistoryAuthorization` (`health_repository.dart:71-295`). No `writeHealthData`/`writeWorkoutData` anywhere.
- Types (`lib/features/health/models/health_metric.dart:162-289`): BODY_FAT_PERCENTAGE, LEAN_BODY_MASS, HEIGHT, WEIGHT, BLOOD_PRESSURE_SYSTOLIC/DIASTOLIC, HEART_RATE (daily average), RESTING_HEART_RATE, BLOOD_OXYGEN (daily avg), SLEEP_* (asleep/light/deep/rem/awake), STEPS, DISTANCE_DELTA, ACTIVE_ENERGY_BURNED. No ExerciseSessionRecord.
- Written as `MeasurementEntry` rows (PowerSync `measurements` tables) with deterministic UUIDv5 `external_id` for dedup (`health_entry_writer.dart:41-300`, `health_importer.dart:31-50`). Orchestration: `health_sync.dart`, `health_importer.dart`.
- Manifest permissions (`AndroidManifest.xml:12-28`): `android.permission.health.READ_WEIGHT, READ_BODY_FAT, READ_LEAN_BODY_MASS, READ_HEIGHT, READ_BLOOD_PRESSURE, READ_HEART_RATE, READ_RESTING_HEART_RATE, READ_OXYGEN_SATURATION, READ_SLEEP, READ_STEPS, READ_DISTANCE, READ_ACTIVE_CALORIES_BURNED, READ_HEALTH_DATA_HISTORY`; `<queries>` for `com.google.android.apps.healthdata` and `ACTION_SHOW_PERMISSIONS_RATIONALE` (`:30-40`); rationale intent filter (`:95-98`) and `ViewPermissionUsageActivity` alias (`:101-111`); `MainActivity.kt` just opens the privacy policy for those intents.
- Note: if our watch session HR ended up in Health Connect on the phone, the existing importer would only pick it up as daily-average heart-rate measurements, not as a workout.

---

## 4. Wear OS

- **No wear module, no Data Layer, no `play-services-wearable`, no watch code.** `android/settings.gradle` includes only `:app`. Only native source: `android/app/src/main/kotlin/de/wger/flutter/MainActivity.kt` (FlutterFragmentActivity, privacy-policy intents).
- Only "wear" hits: a screenshot device enum `androidWear('wearScreenshots')` in `integration_test/make_screenshots_test.dart:43`, which is not used anywhere else (no `wearScreenshots` folder in `fastlane/metadata/android/en-US/images/`). Other hits are comments about "wearables" writing health data (`health_metric.dart:26,216`, `health_importer.dart:120`, `health_entry_writer.dart:308`).
- GitHub issues not checked (offline/read-only clone).

---

## 5. Android packaging

- `applicationId "de.wger.flutter"`, `namespace = "de.wger.flutter"` (`android/app/build.gradle:24,41`); debug build adds `applicationIdSuffix ".debug"` and name "wger - debug" (`:64-70`). **No product flavors.**
- `compileSdk = 37` (`:25`), `minSdkVersion 26` (`:42`), `targetSdk = flutter.targetSdkVersion` (`:43`, from Flutter SDK 3.47.7 stable — `.github/actions/flutter-common/action.yml:11-12`), versionCode/Name from Flutter (`:44-45`; CI sets version from git tag and build number from Play, `pubspec.yaml:19-23`). Java/JVM 17 (`:33-36,74-78`), NDK 28.2.
- AGP **9.2.1**, Kotlin **2.3.20** (`android/settings.gradle` plugins block), Gradle **9.6.1** (`gradle-wrapper.properties`), `android.builtInKotlin=true`, `android.newDsl=false` (`android/gradle.properties`).
- **Signing**: `signingConfigs.release` reads `fastlane/metadata/envfiles/key.properties` (`build.gradle:15-20,52-63`). The keystore `keys.jks`, `key.properties` and the Play API key `playstore.json` are committed GPG-encrypted (`fastlane/metadata/envfiles/*.gpg`) and decrypted in CI by `decrypt_secrets.sh` with secrets `DECRYPTKEY_PLAYSTORE_SIGNING_KEY`, `DECRYPTKEY_PROPERTIES`, `DECRYPTKEY_PLAYSTORE` (`.github/workflows/build-android.yml:14-18,36-44,66-75`). Build: `flutter build apk --release` / `flutter build appbundle --release` (`build-android.yml:47,78`). Upload with fastlane `upload_to_play_store` (production / alpha lanes, `fastlane/Fastfile:41-70`; `Appfile` package `de.wger.flutter`), triggered from `make-release.yml:37-105`. Whether `keys.jks` is the app-signing key or a Play App Signing upload key is not stated in the repo.

### What a separate Wear APK needs
- Same `applicationId` `de.wger.flutter` and same signing certificate as the phone app (Data Layer delivers only between same package + same signature). With Play App Signing, sign the wear bundle with the same upload key; Play re-signs both with the app-signing key.
- Upload the wear AAB to the **same Play listing** (Wear OS form factor enabled in Play Console; dedicated Wear OS release track or same track as multi-APK). Wear manifest needs `<uses-feature android:name="android.hardware.type.watch"/>` and `com.google.android.wearable.standalone` meta-data; versionCode must not collide with the phone's (phone uses Play-incremented build numbers, so use a disjoint range/offset).
- Phone side would need native code for the Data Layer (a `WearableListenerService` in `android/app/src/main/kotlin`, or a Flutter plugin + platform channel) — nothing exists today.
- Debug: phone debug id is `de.wger.flutter.debug`, so a wear debug build must use the same suffix to talk to it.

### Can the Flutter Android project host `:wear`?
Plausibly yes: it is a plain Gradle build (`android/settings.gradle`), AGP application plugin already declared (`apply false`), so `include ":wear"` with `id "com.android.application"` (+ `org.jetbrains.kotlin.plugin.compose` 2.3.20) works without the Flutter gradle plugin. Caveats: root `build.gradle` forces `evaluationDependsOn(':app')` and redirects `buildDir` to `../build/<name>` for all subprojects, plus `afterEvaluate` compileSdk/namespace hooks (harmless); `flutter build appbundle` only builds `:app`, so CI needs an extra `./gradlew :wear:bundleRelease` step and a fastlane upload for the wear bundle; reuse the same `key.properties` signing block. `android.builtInKotlin=true` means AGP 9's built-in Kotlin (don't also apply `kotlin-android`).

---

## 6. Workout logging

- **Session**: rows in `manager_workoutsession` via Drift → PowerSync (`lib/features/routines/providers/workout_session_repository.dart:99-111`). Gym mode's session page reuses an open session for the routine (no end, started ≤ 5 h ago; `sessionMaxDuration = Duration(hours: 5)`, `session.dart:62`) else one from the same calendar day, else creates `WorkoutSession(dayId, datetimeStart: workoutStart, routineId)`; prefills `datetimeEnd = now` (`lib/features/routines/widgets/gym_mode/session_page.dart:56-80`). Fields written: id (uuid v7), routine_id, day_id, notes, impression, datetime_start, datetime_end (`session.dart:133-146`). Datetimes stored as UTC ISO-8601 (`date_only_text_converter.dart:58`).
- **Log**: built per set from the server-computed `SetConfigData` (`Log.fromSetConfigData`, `lib/features/routines/models/log.dart:98-119`) with `routineId` and `iteration` from gym state (`gym_state_notifier.dart:308-323`). Sets: date = now, slot_entry_id, exercise, weight + weight_target, `weight_unit_id` = config unit or user's metric preference unit or `WEIGHT_UNIT_KG = 1` (`LB = 2`, `consts.dart:59-60`), repetitions + repetitions_target, `repetitions_unit_id` default `REP_UNIT_REPETITIONS_ID = 1` (`consts.dart:56`), rir + rir_target. User edits RiR in `log_page.dart:358-361`. Saved with `logProvider.addEntry(log, dayId: gymState.dayId)` (`log_page.dart:389`).
- `WorkoutLogRepository.addLocalDrift` (`workout_logs_repository.dart:121-204`): in one transaction, attaches the log to a session — covering session, else open session ≤ 5 h, else time-less same-day session, else **creates a lazy session with `day_id`** so `need_logs_to_advance` days advance (comments reference wger#2460, `:113-117,189-200`). Then inserts the log. Columns written: `log.dart:178-198`.
- **"Today's" day**: no client logic for advancing; it uses the server-computed date sequence. `GET /routine/{id}/date-sequence-display/` and `/date-sequence-gym/` return `DayData {iteration, date, label, day, slots}` (`routines_repository.dart:79-114`, `day_data.dart:26-50`). `Routine.getIteration()` finds the entry whose `date` is today (`routine.dart:193-202`); current iteration list `dayDataCurrentIteration*` (`:204-236`); the UI marks the entry with `date == today` with a "today" icon (`widgets/day.dart:159`, dashboard `routines.dart:227`) and starts gym mode with `GymModeArguments(routineId, dayId, iteration)` (`day.dart:165`, `screens/gym_mode.dart:24-29`). Gym state picks `dayDataGym` by `(iteration, dayId)` (`gym_state.dart:290-293`). `need_logs_to_advance` is only a Day model field/form toggle (`day.dart:74-75`, `widgets/forms/day.dart:289-295`); advancement is server-side, keyed off sessions having `day_id`.

---

## Takeaways for our watch project
1. Sessions/logs already flow through PowerSync with client-generated UUIDv7 ids and an idempotent upsert endpoint. A phone-side bridge could insert watch-produced sessions/logs directly into the app's local PowerSync DB (`manager_workoutsession` / `manager_workoutlog`) and let the existing queue upload them. Uploads are idempotent by row id, so generate UUIDs on the watch.
2. Routine structure and today's day come only from REST (`structure`, `date-sequence-gym`) and aren't persisted, so the phone (or watch) must fetch and cache them for offline watch use.
3. Auth is JWT + rotating refresh tokens (allauth headless). Don't share the phone's refresh token; relay through the phone, or give the watch its own session.
4. Set `day_id` on sessions, `iteration`, `slot_entry_id`, `routine` and unit ids on logs to match app behaviour and keep `need_logs_to_advance` working.
5. Packaging: `de.wger.flutter`, AGP 9.2.1 / Kotlin 2.3.20 / compileSdk 37 / minSdk 26, release key GPG-encrypted in repo + CI secrets; a `:wear` Gradle module inside `android/` is feasible but needs separate CI/fastlane steps.
