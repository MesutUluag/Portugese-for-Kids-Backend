# AGENT.md — AI Agent Guide for Portuguese for Kids Backend

This file describes the architecture, conventions, and rules an AI coding agent must follow when working on this repository.

---

## Project Purpose

Spring Boot REST API that serves AI-generated European Portuguese sentences and illustrations for a children's language-learning app. The frontend is a React + TypeScript SPA hosted on GitHub Pages.

---

## Repository Layout

```
src/main/java/com/mesutuluag/portugeseforkidsbackend/
├── commons/RateLimitService.java         # Shared per-IP daily rate limiter
├── exception/DailyLimitExceededException.java
├── story/
│   ├── StoryController.java              # POST /api/story — context routing + LLM call
│   ├── StoryRequest.java                 # Request DTO (prompt, context, previousSentence, conversationHistory)
│   ├── StoryResponse.java                # Response DTO (single "content" string field)
│   └── StoryPage.java                    # JSON record (pt, en, mainEmoji, bgLeft, bgRight, imagePrompt)
└── image/
    ├── ImageController.java              # GET /api/image — delegates to ImageService
    ├── ImageService.java                 # Provider chain: cache → Gemini → Pollinations → HF
    ├── GeminiImageClient.java            # Gemini GenAI image provider
    ├── PollinationsImageClient.java      # Pollinations fallback (no auth)
    ├── HuggingFaceImageClient.java       # HF Stable Diffusion 3 fallback
    ├── SemanticImageCache.java           # In-memory LRU + TF-IDF cosine similarity
    └── GcsImageStore.java                # GCS persistence (optional, conditional bean)

src/main/resources/
├── application.properties
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
├── story/StoryControllerTest.java        # Unit (Mockito, no network)
├── story/StoryEvaluatorTest.java         # @Tag("ai-eval") — needs live GCP credentials
└── image/SemanticImageCacheTest.java     # Unit (no network)
```

---

## Key Design Decisions

### Context routing in `StoryController`
All system prompts are loaded from classpath at startup into a `Map<String, String>`. The `context` field in the request body selects the right prompt. Unknown values fall back to `school`. **Do not add inline prompt strings** — always add a new `.md` file under `prompts/` and register it in the constructor map.

### Conversation continuation
`StoryController.createStory` appends two optional blocks to the user prompt before calling the LLM:
1. `conversationHistory` (last 10 entries) — prevents sentence repetition.
2. `previousSentence` — instructs the model to reply logically to the previous turn.

### LLM response cleaning
The raw string from the chat client is passed through two cleanup steps before JSON parsing:
1. Strip markdown code fences (` ```json … ``` `).
2. Extract the first `{…}` block in case the model prepends/appends stray text.

A lenient `ObjectMapper` (duplicate-key last-value-wins, unknown fields ignored) is then used to parse the JSON into `StoryPage`.

### Image provider chain (ImageService)
Resolution order: **semantic cache → Gemini → Pollinations → Hugging Face → null (503)**. Only Gemini results are JPEG-compressed and cached. Pollinations and HF results are returned as-is and not cached.

### Semantic cache (SemanticImageCache)
TF-IDF cosine similarity with a threshold of **0.85**. Backed by a `LinkedHashMap` with LRU eviction. A `ReentrantReadWriteLock` guards all access. When `image.cache.gcs.enabled=true`, `GcsImageStore` is injected as an `Optional<GcsImageStore>` and loaded synchronously at startup; writes are fire-and-forget on a virtual-thread executor. `GcsImageStore` is a conditional bean — absent when the property is not `true`.

### Rate limiting (RateLimitService)
Key = `LocalDate.now() + ":" + ipAddress`. Limit = **250 per IP per calendar day**. Shared across both endpoints. Disable for local testing with `rate-limit.enabled=false` in `application-local.properties`.

---

## Conventions to Follow

| Area | Rule |
|---|---|
| **Package structure** | One sub-package per feature (`story`, `image`). Shared infrastructure lives in `commons` or `exception`. Do not place classes directly in the root package. |
| **DTOs** | Use plain Java classes with getters/setters for request DTOs (`StoryRequest`). Use records for immutable data (`StoryPage`, `GcsImageStore.Entry`). |
| **Spring beans** | Controllers and Services are Spring-managed (`@RestController`, `@Service`). Image clients are `@Component`. Inject via constructor — no field injection. |
| **Configuration** | All tunable values must be bound via `@Value` or `application.properties`. No magic literals inside business logic. |
| **Logging** | Use SLF4J (`LoggerFactory.getLogger`). Log at `WARN` for non-fatal provider failures and `ERROR` only for total failures. Never log secrets or full request bodies. |
| **Error handling** | `DailyLimitExceededException` is `@ResponseStatus(429)` and propagates from the controller. Image provider failures return `503` with a JSON body — do not throw exceptions to the client. |
| **CORS** | Both controllers declare identical `@CrossOrigin` origins. If you add a new controller, copy the same annotation. |
| **Tests** | Unit tests must not make network calls. AI evaluator tests must be tagged `@Tag("ai-eval")`. New image cache behaviour goes in `SemanticImageCacheTest`. |

---

## Adding a New Context

1. Create `src/main/resources/prompts/story-system-prompt-<key>.md` following the schema of an existing prompt (role, goal, prioritized topics, conversation reply rule, output rules, JSON schema, example).
2. Register the key in `StoryController` constructor: `this.systemPrompts.put("<key>", loadPrompt("story-system-prompt-<key>.md"));`
3. Add `<key>` to the **Supported `context` values** table in `README.md`.
4. Add at least one unit test in `StoryControllerTest` verifying the new context is routed correctly.

---

## Adding a New Image Provider

1. Create a new `@Component` class in the `image` package implementing a `generate(…)` method that returns `byte[]` or `null`.
2. Inject it into `ImageService` via the constructor.
3. Insert it at the correct position in the fallback chain in `ImageService.generateJpeg`.
4. Document it in the **Image Provider Chain** table in `README.md`.

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
