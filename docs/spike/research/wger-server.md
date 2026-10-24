# wger server 2.7: PowerSync, auth, routines and logging (feasibility spike for a Wear OS/phone client)

Sources:
- `/tmp/claude-0/up/wger-2.7`: tag `2.7`, `VERSION_STRING='2.7.0'`. Cited below as `path:line`.
- `/tmp/claude-0/up/wger`: master, `2.8.0-dev.2` (commit 9f38787).
- The sync rules are not in the server repo. They live in the public `wger-project/docker` repo, which I shallow-cloned to `/tmp/claude-0/research/docker-repo` (master, commit 8a1a9d3, 2026-10-23). That copy is newer than the 2.7 tag and may differ slightly from what shipped with 2.7.
- allauth 65.19.0 (the version pinned in `pyproject.toml:34`, `django-allauth[mfa,idp-oidc]~=65.19.0`) is unpacked at `/tmp/claude-0/research/allauth-dl/django_allauth-65.19.0`.

---

## 1. How PowerSync is wired

### The pieces

**Upload infrastructure: `wger/utils/powersync.py`**
- Holds a `REGISTRY` that maps a table name to its handler (`:43`).
- `PowerSyncHandler` is the base class (`:46-278`). `@register_handler` registers each handler under `model._meta.db_table` (`:281-296`).
- Each app registers its handlers in its own `wger/<app>/powersync.py`. These modules are imported for their side effect in `wger/core/api/views.py:70-76`.

**Token minting: `wger/core/api/powersync.py`**
- `create_token()` (`:47-60`) and `public_jwk()` (`:42-44`).
- Keys are read from `settings.JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY`, stored as base64url-encoded JWK JSON (`:32-44`).

**Endpoints (`wger/urls.py`, under the `i18n_patterns` block):**

| Endpoint | Code | Notes |
|---|---|---|
| `GET /api/v2/powersync-token` | `wger/urls.py:354-358` → `get_powersync_token` (`wger/core/api/views.py:365-379`) | `IsAuthenticated`. Returns `{token, powersync_url}`. The URL is `SITE_URL/POWERSYNC_URL_PATH/` (default path `ps`), or `POWERSYNC_URL` if that is set. |
| `GET /api/v2/powersync-keys` | `wger/urls.py:359-363` → `get_powersync_keys` (`wger/core/api/views.py:393-395`) | JWKS `{keys:[public_jwk]}`, unauthenticated. The PowerSync service reads it through `PS_JWKS_URL=http://web:8000/api/v2/powersync-keys` (docker `services/powersync.yaml`). |
| `PUT\|PATCH\|DELETE /api/v2/upload-powersync-data` | `wger/urls.py:364-368` → `upload_powersync_data` (`wger/core/api/views.py:423-480`) | The CRUD upload endpoint. See §2. |

**Settings**
- `settings/main.py:78`: `PS_STORAGE_PG_URI`, default `postgres://powersync_storage:powersync_password@db:5432/wger`.
- `settings/main.py:100-105`:
  - `JWT_PUBLIC_KEY` and `JWT_PRIVATE_KEY` (RS256)
  - `POWERSYNC_URL_PATH`, default `ps`
  - `POWERSYNC_URL`
  - `POWERSYNC_TOKEN_LIFETIME`, default 600
- `settings/main.py:106-122`: warns when the keys are the shipped defaults (`_DEFAULT_JWT_KEY_HASHES`, `:48-50`) or when they are unset.
- `settings/settings_global.py:322-324`: `POWERSYNC_TOKEN_LIFETIME = 600`.

**Management commands**
- `generate-jwt-keys` (`wger/core/management/commands/generate-jwt-keys.py:28-79`):
  - Generates an RSA 2048 key (`--key-size`) with `alg=RS256` and `kid` defaulting to `wger` (`--kid`).
  - Prints `JWT_PRIVATE_KEY=` and `JWT_PUBLIC_KEY=` as base64url JWK.
  - One keypair is shared by SimpleJWT, allauth.headless and PowerSync; the `aud` claim tells them apart (docstring at `:29-34`).
  - The default key shipped in docker `config/prod.env:18-19` has `kid "powersync"`.
- `setup-powersync-storage` (`.../setup-powersync-storage.py:27-142`): an idempotent command that creates the storage role from `PS_STORAGE_PG_URI` plus schema `powersync` (`CREATE SCHEMA ... AUTHORIZATION`, `GRANT CONNECT`). It does nothing if the URI is unset or the database is not Postgres.

**Publication migrations**
- `core/0023_create_publication.py:10-14`: now a no-op. It used to run `FOR ALL TABLES`, which needs a superuser.
- `core/0027_powersync_publication.py:23-54`: `CREATE PUBLICATION powersync FOR TABLE <explicit list>`, only if no publication exists yet (`:57-73`). Installs older than 2.7 keep their existing `FOR ALL TABLES` publication. The explicit list:
  - core: `core_language`, `core_license`, `core_repetitionunit`, `core_userprofile`, `core_weightunit`
  - exercises: `exercises_*` (alias, equipment, exercise, M2M tables, category, comment, image, video, muscle, translation)
  - `gallery_image`
  - manager: **`manager_routine`, `manager_workoutlog`, `manager_workoutsession`**
  - measurements: `measurements_category`, `measurements_measurement`
  - nutrition: `nutrition_image`, `nutrition_ingredientweightunit`, `nutrition_logitem`, `nutrition_meal`, `nutrition_mealitem`, `nutrition_nutritionplan`, `nutrition_synced_ingredient`
- **`manager_day`, `manager_slot`, `manager_slotentry` and all `manager_*config` tables are NOT in the publication and are not synced.**

### Sync rules (docker repo, `services/config-powersync/sync_rules.yaml`)

The format is **Sync Streams**, `config: edition: 3` (`:34-35`), with `streams:` and `auto_subscribe: true`. It does not use legacy `bucket_definitions`. The file header says "Sync rules version: 1" (`:10`).

| Stream | Tables | Scope |
|---|---|---|
| `core` (`:39-57`) | language, license, repetitionunit, weightunit, all `exercises_*` | global |
| `user_profile` (`:60-64`) | `core_userprofile`, `gallery_image` | `CAST(user_id AS TEXT) = auth.user_id()` |
| `user_ingredients` (`:67-89`) | `nutrition_synced_ingredient` (aliased as `nutrition_ingredient`), `nutrition_image`, `nutrition_ingredientweightunit` | the ingredients this user references (CTE) |
| `user_planning` (`:93-117`) | **`manager_routine` WHERE user AND `is_template = FALSE`**, `measurements_category`, `nutrition_nutritionplan`/`meal`/`mealitem` | per user |
| `user_activity` (`:122-143`) | `measurements_measurement` (joined on category.user), **`manager_workoutsession` WHERE user**, **`manager_workoutlog` WHERE user**, `nutrition_logitem` | per user |

Takeaway: through PowerSync a client gets only the routine header rows, sessions and logs. The routine structure (days, slots, entries, configs) and the computed "what to do today" have to come from REST (§4).

Service config, docker `services/config-powersync/powersync.yaml`:
- `client_auth.jwks_uri: !env PS_JWKS_URL`, `audience: ["powersync"]` (`:75-80`)
- `max_parameter_query_results: 10000` (`:66-68`)
- nginx proxies `/ps/` to `powersync:8080` (`config/nginx.conf:65-66`)
- image: `journeyapps/powersync-service:latest`

---

## 2. Writes: how a PowerSync client uploads

wger does not take a PowerSync batch. Clients send one CRUD op per HTTP request to `/api/v2/upload-powersync-data`, with body `{"table": "<db_table>", "data": {...row incl. id...}}` (`views.py:427-437`).

**Verb mapping** (`utils/powersync.py:211-224`):
- `PUT` → create
- `PATCH` → partial update
- `DELETE` → delete

**Status semantics** (`views.py:449-480`):
- `200 {"status":"ok!"}` on success.
- `200 {"error":...,"details":...}` for permanent refusals: validation, FK ownership, unknown table, missing fields. These return 200 because PowerSync retries any non-2xx.
- `503` for transient DB errors (`OperationalError`/`InterfaceError`).
- `500` for anything unclassified (the client retries).
- Unauthenticated requests get `HttpResponseForbidden` (`:425-426`).

**Writable tables (the REGISTRY):**

| Table | Handler | Create | Delete | Notes |
|---|---|---|---|---|
| `manager_workoutlog` | `manager/powersync.py:38-48` | yes | yes | FK check via `WorkoutLogViewSet.get_owner_objects` (routine, session, slot_entry, next_log; `manager/api/views.py:318-328`); `user_id` passed in context |
| `manager_workoutsession` | `:51-57` | yes | yes | FK check on routine and day (`views.py:284-289`) |
| `manager_routine` | `:60-70` | **no** (create goes through REST) | yes | PATCH and DELETE only |
| `core_userprofile` | `core/powersync.py:25-35` | no | no | update only |
| `measurements_category`, `measurements_measurement` | `measurements/powersync.py:37-70` | yes | yes | JSON fields decoded from text |
| `nutrition_nutritionplan`, `meal`, `mealitem`, `logitem` | `nutrition/powersync.py:42-83` | yes | yes | |
| `gallery_image` | `gallery/powersync.py:26-40` | no | yes | |

**Validation and permissions per op** (`utils/powersync.py:143-209`):
1. JSON fields are decoded (`:115-128`), then `preprocess_payload` runs.
2. **Create is idempotent.** If a row with `payload['id']` already exists for this user, the create is acknowledged as a no-op (`:153-156`, `_ack_existing`).
3. FK ownership is checked with `check_fk_ownership` (`utils/viewsets.py:35-75`). A failure gives `{"error":"Forbidden"}`.
4. The DRF serializer runs with `is_valid()`; on create it calls `save(user_id=user_id)` (`:130-137`).
5. Update and delete look the row up by `pk` plus `user_filter=user_id` (`:245-250`). A missing or foreign row is acknowledged as a no-op (`:252-263`).

**Primary keys**
- `WorkoutSession.id = UUIDField(default=uuid7, primary_key=True)` (`manager/models/session.py:63-67`).
- `WorkoutLog.id = UUIDField(default=uuid7, primary_key=True)` (`manager/models/log.py:55-59`).
- Both serializers expose `id` as a writable, optional field (`serializers.py:441-449`, `:581-600`).
- **So a client can generate UUIDs offline (preferably v7) and send them, both via PowerSync PUT and via REST POST.**
- Measurement and Category also use UUID primary keys (`measurements/models/category.py:173`, `measurement.py:76`).
- Routine, Day, Slot and SlotEntry use integer primary keys, which is why routine creation is REST-only (`manager/powersync.py:62-64`).

**Other**
- `WorkoutLogSerializer.session` is an `OwnerScopedSessionField`, writable and restricted to the user's own sessions (`serializers.py:550-578`).
- `WorkoutSessionSerializer` accepts the legacy `date`/`time_start`/`time_end` fields as write-only until 2.8 (`:434-437`, `:476-522`).

---

## 3. Auth

### PowerSync JWT (`core/api/powersync.py:47-60`)
- Claims: `{sub: str(user_id), iat, aud: "powersync", exp: iat + POWERSYNC_TOKEN_LIFETIME (600 s default)}`.
- Header: `kid = jwk['kid']`. Algorithm: `jwk['alg']` (RS256, RSA 2048).
- Minted at `GET /api/v2/powersync-token`, which only requires `IsAuthenticated` (`views.py:364-365`). **Any DRF auth class works**, in the order set at `settings_global.py:522-533`:
  1. `SessionAuthentication`
  2. `TimezoneTokenAuthentication`: DRF `Authorization: Token <key>`, the API key from `/user/api-key`
  3. `HeadlessJWTAuthentication`: allauth headless Bearer JWT
  4. `OidcTokenAuthentication`: opaque OIDC access token; a GET needs scope `api:read`
  5. `TimezoneJWTAuthentication`: SimpleJWT
- The PowerSync JWT is rejected by `/api/v2/` itself (`core/tests/test_headless.py:199-222`).

### allauth headless (used by the Flutter app)
- Installed at `settings_global.py:134-144`; mounted at `/allauth/` (`urls.py:306-307`).
- Settings (`settings_global.py:316-319`):
  - `HEADLESS_CLIENTS=('app',)`
  - `HEADLESS_TOKEN_STRATEGY='wger.utils.headless_auth.WgerJWTTokenStrategy'`
  - `HEADLESS_JWT_ALGORITHM='RS256'`
  - `HEADLESS_JWT_REFRESH_TOKEN_EXPIRES_IN`
- `settings/main.py:287-296` sets `HEADLESS_JWT_PRIVATE_KEY` to the JWT key as PEM, refresh lifetime from env `REFRESH_TOKEN_LIFETIME` (hours, default 2880 = 120 days), SimpleJWT access lifetime from env `ACCESS_TOKEN_LIFETIME` (minutes, default 15).
- `test_headless.py` covers:
  - `headless:app:config` (`:45-49`)
  - `headless:app:account:login` with username/password, which returns `meta.access_token` and `meta.refresh_token`; the access token works as `Bearer` on `/api/v2/` (`:51-70`)
  - `headless:app:tokens:refresh` (`:162-183`)
  - signup gated by `ALLOW_REGISTRATION`
  - deleted or inactive users rejected
  - the PowerSync token rejected
- Concrete paths follow the allauth app client: `/allauth/app/v1/auth/login`, `/allauth/app/v1/tokens/refresh`.

### LongLivedSession (`core/migrations/0028_longlivedsession.py`)
- An index table `(user, session_key unique, created)` over Django sessions that back **long-lived headless refresh tokens**.
- Minted by `mint_long_lived_refresh_token()` (`utils/headless_long_lived.py:51-89`). It creates a dedicated session row and calls allauth's `create_refresh_token`.
- Two places issue these tokens:
  1. The web page `/<lang>/user/api-key` ("new refresh token" button; `core/views/user.py:545-553`), which also lists and revokes them.
  2. **`/<lang>/user/app-auth/?state=<nonce>`** (`core/urls.py:84`, `user.py:576-614`). It requires a login, then redirects to `wger://app-auth#token=<refresh>&state=<nonce>` (scheme and host hardcoded at `user.py:135-136`).
- That custom scheme belongs to the Flutter app, so a third-party app cannot receive the handoff unless it registers the `wger://app-auth` scheme itself. Doing so is fragile because it collides with the official app.
- Copy-pasting a refresh token from `/user/api-key` does work for any client. The client then calls `/allauth/app/v1/tokens/refresh` to get access JWTs.
- There is no username/password `api/v2/login` or `api/v2/token` obtain endpoint in 2.7. Only `api/v2/token/refresh` and `api/v2/token/verify` (SimpleJWT) remain (`urls.py:331-332`).

### OAuth2/OIDC provider: allauth.idp.oidc (new in 2.7)
- Installed `'allauth.idp.oidc'` (`settings_global.py:143-144`).
- Settings (`settings_global.py:327-338`, `settings/main.py:124-127`):
  - `IDP_OIDC_ADAPTER='wger.utils.oidc_auth.WgerOIDCAdapter'`
  - `IDP_OIDC_PRIVATE_KEY` (PEM, from env; empty means the provider is off)
  - `IDP_OIDC_REFRESH_TOKEN_EXPIRES_IN = 120 days`
- Key generator: `manage.py generate-oidc-key`. Docs: wger.readthedocs.io `administration/oauth2_provider.html` (referenced in docker `prod.env:179-183`).
- URLs (`urls.py:308-312`, from `allauth/idp/oidc/urls.py`):
  - `/.well-known/openid-configuration`
  - `/.well-known/jwks.json`
  - `/identity/o/authorize` (wrapped by wger, 404 if the provider is unconfigured; `oidc_auth.py:53-64`)
  - **`/identity/o/device`** (user-code entry page)
  - `/identity/o/logout`
  - `/identity/o/api/token`
  - `/identity/o/api/revoke`
  - **`/identity/o/api/device/code`**
  - `/identity/o/api/userinfo`
  - `introspect` and `clients` (DCR) only when enabled
- Grant types (`allauth/idp/oidc/models.py:40-44`): `authorization_code`, **`urn:ietf:params:oauth:grant-type:device_code`**, `client_credentials`, `refresh_token`. A grant type works only if it is enabled on the client (`test_oidc_provider.py:485-499`).
- **PKCE S256** is advertised and enforced: discovery lists `S256` (`test_oidc_provider.py:222`); a missing or wrong verifier is rejected (`:291-310`).
- Public clients are supported (`Client.Type.PUBLIC`, `:124-131`).
- Scopes: `openid`, `profile`, `email` plus wger's **`api:read`** and **`api:write`** (`oidc_auth.py:37-43`). Enforced per request: safe methods need `api:read`, everything else `api:write` (`oidc_auth.py:77-97`). Users can grant a subset of the requested scopes (`test:395-423`).
- allauth defaults: access token **opaque**, lifetime 3600 s; refresh rotation on; device code lifetime 300 s with a 5 s polling interval.
- **Client registration:** dynamic client registration (DCR) is off by default (`IDP_OIDC_DCR_ENABLED=False`). Clients are created by an admin in the Django admin (`allauth/idp/oidc/admin.py:12`, `ClientAdmin`).
- Users review and revoke connected apps at `/<lang>/user/connected-applications` (`core/views/oidc.py:94-121`). Expired tokens are cleaned by a Celery task (`core/tasks.py:85-93`).

**Can a Wear OS app get tokens?** Yes, two ways, once the self-hoster sets `IDP_OIDC_PRIVATE_KEY` and creates a public client:
- **Auth code + PKCE:** the phone opens a browser with a custom-scheme or app-link redirect.
- **Device authorization grant:** the watch shows a user code and the user enters it at `/identity/o/device`. This requires enabling the `device_code` grant on the client.

The resulting opaque access token works on `/api/v2/...` and on `/api/v2/powersync-token` (GET needs `api:read`; upload PUT/PATCH/DELETE needs `api:write`).

Caveats:
- The wger code only tests the auth code and refresh flows. The device flow comes from allauth and is untested in wger.
- On master, `authorization_view` additionally refuses requests while a trainer is impersonating a member (`master oidc_auth.py:66-69`).

---

## 4. "Today's workout" and `need_logs_to_advance`

**Endpoints** (`manager/api/views.py:128-161`):
- `GET /api/v2/routine/{id}/date-sequence-gym/` and `/date-sequence-display/`: unpaginated and cached. Both serialize `Routine.date_sequence`. Each element is `{iteration, date, label, day: DaySerializer, slots: [{comment, is_superset, exercises[], sets:[SetConfigData]}]}` (`serializers.py:365-427`).
- `SetConfigData` fields: `slot_entry_id`, `exercise`, `sets`, `max_sets`, `weight`, `max_weight`, `weight_unit`, `weight_rounding`, `repetitions`, `max_repetitions`, `repetitions_unit`, `repetitions_rounding`, `rir`, `max_rir`, `rpe`, `rest`, `max_rest`, `type`, `text_repr`, `comment`.
- Also available: `/structure/` (full tree), `/logs/`, `/stats/`.
- There is no "active routine" concept on the server; the client picks the routine and today's entry by date.

**`Routine.date_sequence`** (`manager/models/routine.py:176-331`):
- Days are walked in `order`, from `routine.start` to `routine.end`. `iteration_counter[day]` starts at 1.
- `workout_session_map[day.id]` = the set of `session.local_day_in(user tz)` for **WorkoutSessions whose `day` FK is that day** (`:226-242`, prefetched `day.workoutsession_set`).
- The advance rule (`:262-273`):
  ```python
  has_session = previous_date in workout_session_map[current_day.id]
  can_proceed = (not current_day.need_logs_to_advance
                 or (current_day.need_logs_to_advance and has_session)
                 or current_date > timezone.localdate(timezone=tz))
  ```
  When it can proceed, that day's iteration is incremented and the walk moves to the next day (`:275-280`).
- **This means the gate is a WorkoutSession, not individual logs.** The session must have `day = <that Day id>` and a `datetime_start` whose local date (user `UserProfile` timezone, added in core 0029) is the day **before** the date being evaluated, so the routine advances the following day. Log fields are not consulted directly.
- When the sequence contains need-logs days, its cache TTL is capped at local midnight (`:317-329`).
- `data_for_day()` uses the owner's local "today" (`:333-346`).

**How a session gets its `day`:**
- Set it explicitly on POST/PUT of the session (`WorkoutSessionSerializer` fields include `day`, `serializers.py:441-449`; ownership is checked).
- Or let the server derive it:
  - When a log has no session, `WorkoutLog.assign_session()` (`log.py:257-307`) finds an existing session covering the log date, or an open session started within `WGER_MAX_SESSION_LENGTH_HOURS`, or creates one with `day = slot_entry.slot.day` (only if the slot entry belongs to the same routine).
  - Backfill shim (`log.py:341-358`): after saving a log that has a `slot_entry`, if the session's `day` is null and all of the session's slot-entry logs agree on one day of the same routine, `session.day` is set. The comment says this shim is temporary.
- **Minimum for a day to count as done:** a session with `routine` and `day` set (or logs carrying `routine` plus a `slot_entry` from that day, so the server fills `day`), and a correct `datetime_start`.

**Progression** is a separate mechanism (`slot_entry.py:371-470`):
- Configs with requirements only apply once **logs of the previous iteration** (`log.iteration == i-1`, filtered on `slot_entry.workoutlog_set`) meet the thresholds.
- So logs should carry **`slot_entry` and `iteration`**, where iteration is the date-sequence `iteration` for that day. Without them, auto-progression never fires.

---

## 5. WorkoutLog and WorkoutSession REST in 2.7

**`POST /api/v2/workoutsession/`** (`WorkoutSessionViewSet`, `views.py:254-289`)
- Fields: `id`, `routine`, `day`, `notes`, `impression`, `datetime_start`, `datetime_end`.
- All are optional: `datetime_start` defaults to now, `impression` defaults to `'2'` (choices `'1'`, `'2'`, `'3'`), `datetime_end` null means the session is open (`session.py:63-123`).
- Rules: end ≥ start (DB constraint, `:164-170`); duration ≤ `WGER_MAX_SESSION_LENGTH_HOURS` (`clean`, `:179-195`; serializer `validate`, `serializers.py:451-474`).
- Filters: `routine`, `day`, `datetime_start` / `datetime_end` with `date`, `gt`, `gte`, ... lookups (`filtersets.py:26-37`).
- The old `date` field is write-only legacy until 2.8.

**`POST /api/v2/workoutlog/`** (`views.py:292-328`)
- Fields (`serializers.py:581-600`): `id`, `date` (DateTime, default now), `session`, `routine`, `iteration`, `slot_entry`, `next_log`, **`exercise` (required)**, `repetitions_unit` (default 1 = repetitions), `repetitions`, `repetitions_target`, `weight_unit` (default 1 = kg), `weight`, `weight_target`, `rir`, `rir_target`, `rest`, `rest_target`.
- Everything except `exercise` is nullable (`log.py:55-228`).
- `clean()` (`log.py:246-255`): `repetitions` and `weight` cannot both be null; a unit is required when its value is set.
- In 2.7, `save()` raises `DjangoValidationError` without translating it, so a bad log over REST probably returns 500. Master adds `WorkoutLogSerializer.save` to turn it into a 400.
- `save()` silently skips saving if `routine` or `slot_entry` belong to another user (`log.py:315-321`). The viewset FK check rejects that case earlier with 403.
- Session pinning: an own session UUID pins the log; a foreign one gets 403 over REST (`tests/test_log_session_api.py:66-160`) and `{"error":"Forbidden"}` via PowerSync (`:169-230`). With no session, one is auto-created or attached (`:106-131`).

**No bulk endpoint.** `test_log_session_api.py` only covers session pinning on the single-object REST and PowerSync paths. A session and its N logs need N+1 requests over either channel.

**Idempotency**
- Send client-generated UUIDs.
- PowerSync PUT: re-sending the same id is a no-op success (`utils/powersync.py:153-156`).
- REST POST with an existing id: DRF's unique validator on the primary key should return 400 ("already exists"). The client can treat that as already uploaded, or do `GET /workoutlog/{id}/` first. This is inferred from standard DRF behavior; no 2.7 test covers it.
- `assign_session` takes a row lock on the user (`select_for_update`), so concurrent uploads in a batch do not create duplicate sessions (`log.py:275-281`).

**Danger in 2.7 (fixed on master):** `WorkoutLog.routine`/`slot_entry` and `WorkoutSession.routine`/`day` use `on_delete=CASCADE` (`log.py:104-115`, `session.py:69-81`). **Deleting a day or routine on the server deletes the user's history.** Master migration `manager/0030_preserve_workout_history.py` changes these to `SET_NULL`.

---

## 6. Health sync

`measurements/migrations/0006_health_sync.py` adds:
- On `Category`: `order`, `parent` FK (self), `metric_type` (custom, body_weight, body_fat, lean_body_mass, height, blood_pressure (+systolic/diastolic), **heart_rate**, **resting_heart_rate**, blood_oxygen, steps, distance, energy, sleep (+total/light/deep/rem/awake)), `chart_type`, `chart_config` JSON. `unit` becomes blank-able. New constraint: one typed category per user (`unique_typed_category_per_user`, applies when `metric_type != custom`).
- On `Measurement`: **`source`** (`user`, `google`, `apple`, `calculated`; default `user`) and **`external_id`** (UUID, nullable). New `UniqueConstraint(category, source, external_id)` when `external_id` is not null. `value` widened to `numeric(8,2)`. New index on `(category, -date, -id)`.
- Ends with a `RunPython` that runs `UPDATE ... SET id=id` so PowerSync re-replicates the rows.

Later migrations:
- `0007_migrate_weight.py`: adds `Category.is_official` and `Measurement.extra_data` JSON (provenance: unit, original value, device; `measurement.py:121-130`), and moves weight entries into measurements.
- `0008_dynamic_type.py`: adds `Category.dynamic_params` / `dynamic_type`.

There is a dummy generator, `measurements/management/commands/dummy-generator-health-measurements.py`, that imitates the "Apple Health / Health Connect importer".

**WorkoutSession has no heart-rate or calorie fields** (`session.py:32-123`), and the manager app has no heart-rate code. Options for heart rate:
- Store it as Measurements in a `heart_rate` category, with `source='google'` and `external_id=<Health Connect record UUID>` for deduplication.
- Or put a summary in the session's `notes`.

---

## 7. Versions and API versioning

`wger/version.py`:
- `MIN_APP_VERSION = 2.1.0`: the minimum Flutter app version the server accepts.
- `MIN_SERVER_VERSION = 2.5.0`: the minimum server version for sync commands.
- `VERSION_STRING = '2.7.0'` (master: `2.8.0-dev.2`, with the same minimums).

Endpoints, all `AllowAny` (`core/api/views.py:178-274`, `urls.py:333-353`):
- `GET /api/v2/version/` → `"2.7.0"`
- `GET /api/v2/min-app-version/`
- `GET /api/v2/min-server-version/`

There is no DRF `DEFAULT_VERSIONING_CLASS`. The only version marker is the `/api/v2/` path prefix. The minimum server version the Flutter app itself requires is set in the Flutter repo, not here. A client should check `/api/v2/version/` ≥ 2.7.0 before relying on:
- `datetime_start` / `datetime_end`
- UUID session ids
- the OIDC provider
- session `day`

---

## 2.7 vs master differences that matter
- `on_delete` CASCADE → SET_NULL for log.routine, log.slot_entry, session.routine and session.day (master `manager/0030`).
- Master returns 400 instead of 500 for `WorkoutLog` model validation errors over REST (`serializers.py` `save` override).
- Master marks `SetConfigData` numeric fields and `WorkoutDayData.label`/`day` as `allow_null`. These are null on fit-in-week padding entries and on missing configs. Kotlin DTOs should be nullable on 2.7 as well.
- Master's OIDC authorize view refuses requests during trainer impersonation; master also adds a trainer-login middleware and a `5/300s/key` failed-login throttle.
- No differences in the PowerSync code, the publication, `urls.py`, the core API views, `routine.py` (date sequence) or the manager API views.
