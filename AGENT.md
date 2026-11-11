# AGENT.md — AI Agent Guide for Portuguese for Kids Backend

This file describes the architecture, conventions, and rules an AI coding agent must follow when working on this repository.

---

## Project Purpose

Spring Boot REST API that serves AI-generated European Portuguese sentences and illustrations for a children's language-learning app. The frontend is a React + TypeScript SPA hosted on GitHub Pages.

---

## Repository Layout

```
src/main/java/com/mesutuluag/portugeseforkidsbackend/
├── commons/RateLimitService.java         # Tiered per-UID / per-IP daily rate limiter (UTC rollover)
├── config/
│   ├── CorsConfig.java                   # Centralised CORS WebMvcConfigurer
│   └── SecurityConfig.java               # OAuth2 Resource Server JWT validation (Firebase Auth)
├── exception/
│   ├── DailyLimitExceededException.java  # 429 exception
│   ├── StoryGenerationException.java     # 502 exception on LLM failure/malformed output
│   ├── UserStoreException.java           # 503 exception on Firestore failure
│   └── GlobalExceptionHandler.java       # @RestControllerAdvice for 400, 429, 502, 503
├── story/
│   ├── StoryController.java              # POST /api/story — rate limiting + delegates to StoryService
│   ├── StoryService.java                 # Context routing, validation, prompt assembly, LLM call & parsing
│   ├── StoryRequest.java                 # Request DTO (prompt, context, previousSentence, conversationHistory)
│   ├── StoryResponse.java                # Response DTO (single "content" string field)
│   └── StoryPage.java                    # JSON record (pt, en, mainEmoji, bgLeft, bgRight, imagePrompt)
├── image/
│   ├── ImageController.java              # GET /api/image — validation + delegates to ImageService
│   ├── ImageService.java                 # Provider chain: cache → Gemini → Pollinations
│   ├── GeminiImageClient.java            # Gemini GenAI image provider (with spend-cap cooldown)
│   ├── PollinationsImageClient.java      # Pollinations fallback (no auth)
│   ├── SemanticImageCache.java           # In-memory LRU + TF-IDF cosine similarity
│   └── GcsImageStore.java                # GCS persistence (optional, conditional bean)
└── user/
    ├── UserController.java               # GET /api/user/me, POST /api/user/stats/sync & GET /api/user/leaderboard
    ├── UserStatsService.java             # Delta sanitization, streak calculation & in-memory fallback
    ├── LeaderboardService.java           # 60s cached global leaderboard & child-privacy display name sanitizer
    ├── FirestoreUserStore.java           # Cloud Firestore transactional persistence & top-N leaderboard query
    ├── UserProfile.java                  # Immutable user profile record
    ├── UserStats.java                    # Immutable user telemetry stats record (including scoreByMode)
    ├── StatsSyncRequest.java             # Request DTO for batched activity deltas
    ├── LeaderboardResponse.java          # Response record for global leaderboard
    └── LeaderboardEntry.java             # Ranked player entry record

src/main/resources/
├── application.properties
├── application-local.properties
└── prompts/                              # One .md system prompt per context key
    ├── story-system-prompt-school.md
    ├── story-system-prompt-restaurant.md
    ├── story-system-prompt-bank.md
    ├── story-system-prompt-hospital.md
    ├── story-system-prompt-cafe.md
    ├── story-system-prompt-airport.md
    ├── story-system-prompt-market.md
    ├── story-system-prompt-aima.md
    ├── story-system-prompt-bus.md
    ├── story-system-prompt-pharmacy.md
    ├── story-system-prompt-gas_station.md
    └── story-system-prompt-traffic.md

src/test/
├── commons/RateLimitServiceTest.java     # Unit (IP resolution, per-UID/IP limits, UTC rollover)
├── config/SecurityConfigTest.java        # Unit (MockMvc JWT auth, 401/200, public leaderboard, CORS preflight)
├── exception/GlobalExceptionHandlerTest.java # Unit (400, 429, 502, 503 responses)
├── story/StoryControllerTest.java        # Unit (Mockito, no network, story rate limiting)
├── story/StoryServiceTest.java           # Unit (Mockito, prompt assembly, validation, JSON parsing)
├── story/StoryEvaluatorTest.java         # @Tag("ai-eval") — needs live GCP credentials
├── image/ImageControllerTest.java        # Unit (validation, clamping, 200/503)
├── image/ImageServiceTest.java           # Unit (provider fallback chain)
├── image/GeminiImageClientTest.java      # Unit (toggle & cooldown)
├── image/PollinationsImageClientTest.java # Unit (mocked HttpClient)
├── image/SemanticImageCacheTest.java     # Unit (no network)
└── user/
    ├── UserControllerTest.java           # Unit (JWT claims extraction, profile/sync/leaderboard endpoints)
    ├── UserStatsServiceTest.java         # Unit (streaks, clamping, allowlists, 30-day pruning)
    └── LeaderboardServiceTest.java       # Unit (Firestore/in-memory leaderboard rankings, child-privacy name sanitization)

.agents/skills/
├── gcs-image-seeding/
│   └── SKILL.md                              # Skill for validating, processing & uploading story images to GCS
└── missing-cache-prompt-analysis/
    └── SKILL.md                              # Skill for analyzing cache misses, tuning SemanticImageCache, and seeding

seed-images/
├── incoming/                                 # Drop incoming raw files here (.png, .jpg, .webp, .txt)
├── processed/                                # Auto-generated 768x336 JPEG & prompt.txt folders (<sha256>/)
├── manifest.json                             # Local registry of staged/processed images (151 entries: M-01 to M-151)
├── prompts.json                              # Custom prompt mapping overrides (e.g. M-01 to M-151)
├── simulate_cache.py                         # Live TF-IDF simulator dynamically loading SemanticImageCache.java rules
├── process_images.py                         # Resizing (768x336), 60% JPEG, SHA-256 staging script (requires Pillow)
├── upload_to_gcs.py                          # GCS push script with metadata & prompt.txt (supports --dry-run and --sha)
└── audit_cache.py                            # Audits GCS cache coverage against all story prompts
```

---

## Key Design Decisions

### Context routing in `StoryService`
All system prompts are loaded from classpath at startup into a `Map<String, String>` inside `StoryService`. The `context` field in the request body selects the right prompt. Unknown values fall back to `school`. **Do not add inline prompt strings** — always add a new `.md` file under `prompts/` and register it in `StoryService.loadAllPrompts()`.

### Conversation continuation
`StoryService.buildUserPrompt` appends two optional blocks to the user prompt before calling the LLM:
1. `conversationHistory` (last 10 entries, configurable via `story.history.max-items`) — prevents sentence repetition.
2. `previousSentence` — instructs the model to reply logically to the previous turn.

### LLM response cleaning
The raw string from the chat client is passed through two cleanup steps before JSON parsing:
1. Strip markdown code fences (` ```json … ``` `).
2. Extract the first `{…}` block in case the model prepends/appends stray text.

A lenient `ObjectMapper` (duplicate-key last-value-wins, unknown fields ignored) is then used to parse the JSON into `StoryPage`.

### Image provider chain (ImageService)
Resolution order: **semantic cache → Gemini (when enabled via `gemini.image.enabled`) → Pollinations → null (503)**. Gemini image generation is disabled by default (`gemini.image.enabled=false`) to prevent costs and is parametric via `GEMINI_IMAGE_ENABLED` (set to `true` to enable). When `GeminiImageClient` encounters a spend-cap breach, `RESOURCE_EXHAUSTED`, or HTTP 429 quota error, it enters a configurable cooldown (`gemini.image.cooldown-minutes=15`) during which `isEnabled()` returns `false` and requests fall through directly to Pollinations (`PollinationsImageClient` with configurable connect/request timeouts). Only Gemini results are JPEG-compressed and cached. Pollinations results are returned as-is and not cached.

### Semantic cache (SemanticImageCache)
TF-IDF cosine similarity with a threshold of **0.55** (`image.cache.similarity-threshold`) and an incremental $O(1)$ document-frequency (`docFreq`) index. Backed by an access-ordered `LinkedHashMap` with LRU eviction (`MAX_ENTRIES = 1000`, `image.cache.max-entries`). A `synchronized` lock guards map mutations and lookups while pure tokenisation/TF calculation runs outside the lock. When `image.cache.gcs.enabled=true`, `GcsImageStore` is injected as an `Optional<GcsImageStore>` and warmed up asynchronously at startup (`image.cache.gcs.async-warmup=true`, capped at `MAX_ENTRIES` with `image.cache.gcs.max-concurrent-reads=32`); writes are fire-and-forget on a virtual-thread executor (shut down via `@PreDestroy`), storing the prompt in `BlobInfo` custom metadata on `image.jpg`. `GcsImageStore` is a conditional bean — absent when the property is not `true`.

### Authentication, User Telemetry & Leaderboard (SecurityConfig, UserStatsService, LeaderboardService, FirestoreUserStore)
- `SecurityConfig` configures Spring Security as a stateless OAuth2 Resource Server (`auth.enabled=true`). `NimbusJwtDecoder` verifies Firebase RS256 ID tokens against Google's JWK set (`auth.firebase.jwk-set-uri`) and validates `exp`/`iat` (with 60s clock skew), `iss` (`https://securetoken.google.com/<project-id>`), `aud` (`<project-id>`), and non-blank `sub`. `GET /api/user/leaderboard` is permitted for unauthenticated guests while still parsing optional Bearer tokens to highlight the caller's standing.
- `UserStatsService` sanitizes incoming `StatsSyncRequest` deltas against strict allowlists (`ALLOWED_MODES`, `ALLOWED_CONTEXTS`), clamps deltas to configured bounds, tracks per-game scores (`scoreByMode`), computes consecutive-day streaks (`currentStreakDays`, `longestStreakDays`), and prunes `dailyTimeSeconds` to the most recent 30 days (`stats.sync.max-daily-history-days=30`).
- When `firestore.enabled=true`, `FirestoreUserStore` persists `UserProfile` documents in the `users` collection (with a top-level indexed `totalScore` field) using Firestore transactions bounded by a `Semaphore(32)` and closes its client on `@PreDestroy`. If Firestore is disabled or unreachable, `UserStatsService` gracefully falls back to an in-memory `ConcurrentHashMap`.
- `LeaderboardService` caches top-N user standings (`getTopLeaderboard` and `getTotalUsersCount`) for 60 seconds, sanitizes child display names to `FirstName L.` (never exposing email addresses), and returns an empty list (`topUsers: []`) when no users exist — never fabricated or mock entries.

### Rate limiting (RateLimitService)
Rate limiting applies exclusively to story generation (`POST /api/story`) to manage LLM quota while keeping images, profile loading, telemetry sync, and leaderboard free of rate limit constraints:
- Authenticated story creation (`POST /api/story`): `today + ":uid:" + uid` → **250/day** (`rate-limit.authenticated-daily-limit`).
- Anonymous story creation: `today + ":ip:" + ipAddress` → **30/day** (`rate-limit.anonymous-daily-limit`).
Behind Cloud Run reverse proxies (GFE), `server.forward-headers-strategy=native` (Tomcat `RemoteIpValve`) and `RateLimitService.resolveClientIp(HttpServletRequest)` ensure the real client IP from `X-Forwarded-For` is extracted without allowing client-spoofed `X-Forwarded-For` prefixes to bypass the limit. When exceeded, `GlobalExceptionHandler` returns HTTP `429` with a `Retry-After` header (seconds until midnight UTC). Disable for local testing with `rate-limit.enabled=false` in `application-local.properties`.

### Input validation & error handling
- `StoryService` enforces length limits on `prompt` (`story.validation.max-prompt-length=300`), `previousSentence` (`story.validation.max-previous-sentence-length=300`), and `conversationHistory` items (`story.validation.max-history-item-length=200`).
- `ImageController` requires a non-blank `imagePrompt` up to `image.validation.max-prompt-length=500` chars and clamps `width`/`height` to `[64, 1024]`.
- `GlobalExceptionHandler` returns structured JSON (`{"error": "...", "message": "..."}`) for `400 Bad Request`, `429 Too Many Requests`, and `502 Bad Gateway` (`StoryGenerationException`).

---

## Conventions to Follow

| Area | Rule |
|---|---|
| **Package structure** | One sub-package per feature (`story`, `image`). Shared infrastructure lives in `commons`, `config`, or `exception`. Do not place classes directly in the root package. |
| **DTOs** | Use plain Java classes with getters/setters for request DTOs (`StoryRequest`). Use records for immutable data (`StoryPage`, `GcsImageStore.Entry`). |
| **Spring beans** | Controllers and Services are Spring-managed (`@RestController`, `@Service`). Image clients are `@Component`. Inject via constructor — no field injection. When a bean has multiple constructors, annotate the Spring-injected constructor with `@Autowired`. |
| **Configuration** | All tunable values must be bound via `@Value` or `application.properties`. No magic literals inside business logic. |
| **Logging** | Use SLF4J (`LoggerFactory.getLogger`). Log at `WARN` for non-fatal provider failures and `ERROR` only for total failures. Never log secrets or full request bodies. |
| **Error handling** | `GlobalExceptionHandler` maps `DailyLimitExceededException` to `429` (with `Retry-After` header), `IllegalArgumentException` to `400`, and `StoryGenerationException` to `502`. Image provider failures return `503` with a JSON body. |
| **CORS** | Centralised in `CorsConfig` (`cors.allowed-origins`). Do not add duplicate `@CrossOrigin` annotations on controllers. |
| **Tests** | Unit tests must not make network calls. AI evaluator tests must be tagged `@Tag("ai-eval")`. New image cache behaviour goes in `SemanticImageCacheTest`. |

---

## Adding a New Context

1. Create `src/main/resources/prompts/story-system-prompt-<key>.md` following the schema of an existing prompt (role, goal, prioritized topics, conversation reply rule, output rules, JSON schema, example).
2. Register `<key>` in `StoryService.loadAllPrompts()`.
3. Add `<key>` to the **Supported `context` values** table in `README.md`.
4. Add at least one unit test in `StoryServiceTest` verifying the new context is routed correctly.

---

## Adding a New Image Provider

1. Create a new `@Component` class in the `image` package implementing a `generate(…)` method that returns `byte[]` or `null`.
2. Inject it into `ImageService` via the constructor.
3. Insert it at the correct position in the fallback chain in `ImageService.generateJpeg`.
4. Document it in the **Image Provider Chain** table in `README.md`.

---

## Image Cache Skills

When working on illustration caching, prompt matching, or GCS seeding, refer to the dedicated skills under `.agents/skills/`:

- **Missing Cache Prompt Analysis ([`.agents/skills/missing-cache-prompt-analysis/SKILL.md`](.agents/skills/missing-cache-prompt-analysis/SKILL.md)):** Use when story prompts miss the cache. Guides running `simulate_cache.py`, fixing vocabulary or domain logic in `SemanticImageCache.java` (Type A), or generating missing seed illustrations via the `image-generator` subagent (Type B).
- **GCS Image Seeding ([`.agents/skills/gcs-image-seeding/SKILL.md`](.agents/skills/gcs-image-seeding/SKILL.md)):** Use for formatting, resizing (`768x336` 60% JPEG via `process_images.py`), auditing coverage (`audit_cache.py`), and syncing processed illustrations to `gs://portugese-kids-image-cache/` (`upload_to_gcs.py`).

> [!IMPORTANT]
> - **Never use Pollinations** to generate seed images (use Gemini Imagen via `image-generator` subagent).
> - **Never upload to GCS** without explicit user confirmation.
> - **Never commit `non-showing-pictures-prompts.md`** to Git.

---

## Running & Validating Changes

```bash
# Unit tests (fast, no credentials)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test

# AI evaluator tests (slow, requires live GCP credentials)
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GOOGLE_CLOUD_PROJECT='your-project-id' \
  ./mvnw test -DskipAiEvalGroups="" -Dgroups="ai-eval"

# Build
./mvnw clean package -DskipTests
```

All unit tests must pass before committing. AI evaluator tests are optional for non-prompt changes.

---

## What NOT to Do

- **Do not** add prompt text as Java string literals. Put prompts in `.md` files.
- **Do not** bind services to `0.0.0.0` in `application.properties` — only in the Dockerfile env or Cloud Run config.
- **Do not** hardcode API keys or tokens anywhere in the source tree.
- **Do not** add `@Autowired` field injection — use constructor injection.
- **Do not** catch and silently swallow exceptions in the image clients — log them and return `null` so the chain falls through.
- **Do not** change the `StoryPage` record fields without updating the system prompts, unit tests, and README simultaneously.
- **Do not** add a `Frequency Penalty` LLM parameter — it was removed because Vertex AI gRPC transport does not support it.
