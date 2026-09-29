# 🇵🇹 Portuguese for Kids — Backend

Spring Boot REST API powering AI-generated story sentences and illustrations for the [Portuguese for Kids](https://github.com/MesutUluag/Portugese-for-Kids) frontend app.

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Runtime |
| Spring Boot | 3.5.16 | Web framework |
| Spring AI | 1.1.8 | LLM integration |
| Google Vertex AI (Agent Platform) | `gemini-3.1-flash-lite` | Story sentence generation |
| Google GenAI SDK | `1.5.0` | Gemini image generation (`gemini-3.1-flash-lite-image`) |
| Google Cloud Storage | `2.50.0` | GCS-backed persistent image cache |
| Pollinations (free) | — | Image generation fallback #1 |
| Hugging Face (Stable Diffusion 3) | — | Image generation fallback #2 |
| Maven | Wrapper included | Build tool |
| Docker | — | Containerisation & Cloud Run deployment |

---

## Project Structure

```
src/main/java/com/mesutuluag/portugeseforkidsbackend/
├── PortugeseForKidsBackendApplication.java   # Spring Boot entry point
├── commons/
│   └── RateLimitService.java                 # Per-IP daily rate limiting (shared)
├── exception/
│   └── DailyLimitExceededException.java      # Custom 429 exception
├── story/
│   ├── StoryController.java                  # POST /api/story
│   ├── StoryRequest.java                     # Request DTO
│   ├── StoryResponse.java                    # Response DTO
│   └── StoryPage.java                        # Story data record
└── image/
    ├── ImageController.java                  # GET /api/image
    ├── ImageService.java                     # Provider orchestration + fallback chain
    ├── GeminiImageClient.java                # Primary provider (Gemini GenAI API)
    ├── PollinationsImageClient.java           # Fallback #1 (free, no auth)
    ├── HuggingFaceImageClient.java           # Fallback #2 (SD3, requires HF token)
    ├── SemanticImageCache.java               # In-memory LRU cache with cosine similarity
    └── GcsImageStore.java                    # GCS persistence for the semantic cache

src/main/resources/
├── application.properties                   # Spring & AI configuration
└── prompts/
    ├── story-system-prompt.md               # Legacy generic system prompt
    ├── story-system-prompt-school.md        # School context
    ├── story-system-prompt-restaurant.md    # Restaurant context
    ├── story-system-prompt-bank.md          # Bank context
    ├── story-system-prompt-hospital.md      # Hospital context
    ├── story-system-prompt-cafe.md          # Café context
    ├── story-system-prompt-airport.md       # Airport context
    ├── story-system-prompt-market.md        # Market context
    ├── story-system-prompt-aima.md          # AIMA (immigration office) context
    ├── story-system-prompt-bus.md           # Bus context
    ├── story-system-prompt-pharmacy.md      # Pharmacy context
    ├── story-system-prompt-gas_station.md   # Gas station context
    └── story-system-prompt-traffic.md       # Traffic context

src/test/java/com/mesutuluag/portugeseforkidsbackend/
├── story/
│   ├── StoryControllerTest.java             # Unit tests (mocked, no network)
│   └── StoryEvaluatorTest.java              # AI evaluator tests (@Tag("ai-eval"))
└── image/
    └── SemanticImageCacheTest.java          # Unit tests for semantic cache logic
```

---

## API Reference

### `POST /api/story`

Generate an A1-level European Portuguese sentence for a given context and optional conversation history.

**Request body**

```json
{
  "prompt": "greet a teacher",
  "context": "school",
  "previousSentence": "Bom dia, aluno!",
  "conversationHistory": ["Bom dia, professora!", "Como te chamas?"]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | ✅ | Topic or instruction for the LLM |
| `context` | string | ❌ | Scene context (see supported values below). Defaults to `school` |
| `previousSentence` | string | ❌ | The previous sentence in the conversation; the model will generate a logical reply |
| `conversationHistory` | string[] | ❌ | Full list of sentences already shown; used to avoid repetition (capped at last 10) |

**Supported `context` values**

`school` · `restaurant` · `bank` · `hospital` · `cafe` · `airport` · `market` · `aima` · `bus` · `pharmacy` · `gas_station` · `traffic`

Unknown values fall back to `school`.

**Response** `200 OK`

```json
{
  "content": "{\"pt\":\"Posso brincar contigo no recreio?\",\"en\":\"Can I play with you at recess?\",\"mainEmoji\":\"🙂\",\"bgLeft\":\"🏫\",\"bgRight\":\"⚽\",\"imagePrompt\":\"two children smiling and playing together in a sunny school playground, colorful cute kids illustration, storybook art, bright colors, simple background, no text\"}"
}
```

The `content` field is a JSON-serialised `StoryPage`:

| Field | Type | Description |
|---|---|---|
| `pt` | string | A1-level European Portuguese sentence (4–8 words) |
| `en` | string | English translation |
| `mainEmoji` | string | Single emoji representing the main subject |
| `bgLeft` | string | Left background decoration emoji |
| `bgRight` | string | Right background decoration emoji |
| `imagePrompt` | string | Image generation prompt — always ends with `"colorful cute kids illustration, storybook art, bright colors, simple background, no text"` |

**Error** `429 Too Many Requests` — daily per-IP limit exceeded.

---

### `GET /api/image`

Generate an illustration from a text prompt. Internally cycles through a provider chain with a semantic cache.

**Query parameters**

| Parameter | Required | Default | Description |
|---|---|---|---|
| `imagePrompt` | ✅ | — | Text description of the image |
| `steps` | ❌ | `10` | Inference steps (used by Hugging Face fallback only) |
| `width` | ❌ | `768` | Output image width in pixels |
| `height` | ❌ | `368` | Output image height in pixels |

**Example**

```
GET /api/image?imagePrompt=a+child+reading+in+a+classroom&steps=20&width=768&height=368
```

**Responses**

| Status | Content-Type | Description |
|---|---|---|
| `200 OK` | `image/jpeg` | Binary JPEG image |
| `429 Too Many Requests` | — | Daily per-IP limit exceeded |
| `503 Service Unavailable` | `application/json` | All image providers failed |

```json
{ "error": "image_unavailable", "message": "Image generation is temporarily unavailable. Please try again later." }
```

---

## Image Provider Chain

`ImageService` resolves images in the following order. Only Gemini results are cached.

| Priority | Provider | Auth | Cached |
|---|---|---|---|
| 1 | **Semantic cache** (in-memory LRU + optional GCS) | — | — |
| 2 | **Gemini** (`gemini-3.1-flash-lite-image` via GenAI API) | `GEMINI_API_KEY` | ✅ Yes (JPEG @ 60% quality) |
| 3 | **Pollinations** (free public API) | None | ❌ |
| 4 | **Hugging Face** (Stable Diffusion 3 via router) | `HF_API_TOKEN` | ❌ |

Returns `null` (→ `503`) only when all four steps fail.

### Semantic Image Cache

`SemanticImageCache` stores JPEG bytes keyed by an in-memory TF-IDF cosine-similarity index. A cache hit is declared when a new prompt has **cosine similarity ≥ 0.85** to a stored prompt, avoiding redundant generation for semantically equivalent requests.

- Bounded LRU map — oldest entry is evicted when the cap is reached.
- Thread-safe via a `ReentrantReadWriteLock`.
- Optional GCS backing (`image.cache.gcs.enabled=true`) — the store is loaded synchronously at startup and written asynchronously (fire-and-forget virtual-thread executor) on each new Gemini result.

---

## Rate Limiting

Both endpoints share a **250 requests per IP address per calendar day** quota tracked in-memory. The counter resets when the server restarts. Rate limiting can be disabled for local testing via `rate-limit.enabled=false`.

---

## CORS Origins

| Origin | Environment |
|---|---|
| `http://localhost:5173` / `http://127.0.0.1:5173` | Vite dev server |
| `http://localhost:8080` / `http://127.0.0.1:8080` | Local preview |
| `https://mesutuluag.github.io` | Production (GitHub Pages) |

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `GOOGLE_CLOUD_PROJECT` | ✅ | — | GCP project ID |
| `GOOGLE_APPLICATION_CREDENTIALS` | ✅ | — | Path to GCP service account JSON key file |
| `HF_API_TOKEN` | ✅ | — | Hugging Face API token |
| `GEMINI_API_KEY` | ❌ | — | Gemini API key for image generation; Gemini image provider is disabled when absent |
| `GOOGLE_CLOUD_LOCATION` | ❌ | `us` | Agent Platform multi-region location (`us` or `eu`) |
| `GOOGLE_CLOUD_API_ENDPOINT` | ❌ | `aiplatform.us.rep.googleapis.com` | Agent Platform gRPC endpoint |
| `SERVER_ADDRESS` | ❌ | `127.0.0.1` | Bind address (`0.0.0.0` for Cloud Run) |
| `PORT` | ❌ | `8081` | HTTP port (`8080` for Cloud Run) |
| `image.cache.gcs.enabled` | ❌ | `true` | Enable GCS-backed image cache |
| `image.cache.gcs.bucket` | ❌ | `portugese-kids-image-cache` | GCS bucket name |
| `image.cache.gcs.prefix` | ❌ | `image-cache` | Object key prefix inside the bucket |

---

## Running Locally

### Prerequisites

- Java 21
- Maven (or use the included `./mvnw` wrapper)
- A GCP service account with the `roles/aiplatform.user` role
- A [Hugging Face](https://huggingface.co) account and API token
- *(Optional)* A Gemini API key for primary image generation

### Start the server

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export GOOGLE_CLOUD_PROJECT='your-gcp-project-id'
export GOOGLE_CLOUD_LOCATION='us'
export GOOGLE_APPLICATION_CREDENTIALS='/absolute/path/to/service-account.json'
export HF_API_TOKEN='hf_xxxxxxxxxxxxxxxxxxxx'
export GEMINI_API_KEY='AIzaXXXXXXXXXXXXX'   # optional but recommended

./mvnw spring-boot:run
```

Server starts at `http://127.0.0.1:8081`.

### Run tests

**Unit tests** (no credentials needed):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

**AI evaluator tests** (requires live GCP credentials with billing enabled):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GOOGLE_CLOUD_PROJECT='your-gcp-project-id' \
  ./mvnw test -DskipAiEvalGroups="" -Dgroups="ai-eval"
```

### Build JAR

```bash
./mvnw clean package
# Output: target/portugese-for-kids-backend-0.0.1-SNAPSHOT.jar
```

---

## Docker

### Build & run locally

```bash
docker build -t portugese-for-kids-backend .

docker run -p 8081:8080 \
  -e GOOGLE_CLOUD_PROJECT='your-gcp-project-id' \
  -e GOOGLE_CLOUD_LOCATION='us' \
  -e GOOGLE_CLOUD_API_ENDPOINT='aiplatform.us.rep.googleapis.com' \
  -e GOOGLE_APPLICATION_CREDENTIALS='/app/service-account.json' \
  -e HF_API_TOKEN='hf_xxxxxxxxxxxxxxxxxxxx' \
  -e GEMINI_API_KEY='AIzaXXXXXXXXXXXXX' \
  -e SERVER_ADDRESS='0.0.0.0' \
  -e PORT='8080' \
  portugese-for-kids-backend
```

---

## Deploy to Google Cloud Run

Deployments are **automated via GitHub Actions** — every push to `main` builds the Docker image, pushes it to GCR, and deploys to Cloud Run. See [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml).

### Required GitHub secret

| Secret | Description |
|---|---|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Workload Identity Federation provider resource name (keyless auth — no JSON key stored) |

### One-time GCP setup

Run these once to wire up the CI/CD pipeline:

```bash
PROJECT=project-b933c218-2521-4f77-a37
SA=portugese-for-kids-backend@$PROJECT.iam.gserviceaccount.com

# 1. Create Workload Identity Pool
gcloud iam workload-identity-pools create "github-pool" \
  --project=$PROJECT --location=global \
  --display-name="GitHub Actions Pool"

# 2. Create OIDC provider (scoped to your GitHub account)
gcloud iam workload-identity-pools providers create-oidc "github-provider" \
  --project=$PROJECT --location=global \
  --workload-identity-pool="github-pool" \
  --display-name="GitHub Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository_owner == 'MesutUluag'" \
  --issuer-uri="https://token.actions.githubusercontent.com"

# 3. Allow the GitHub repo to impersonate the service account
gcloud iam service-accounts add-iam-policy-binding $SA \
  --project=$PROJECT \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$(gcloud projects describe $PROJECT --format='value(projectNumber)')/locations/global/workloadIdentityPools/github-pool/attribute.repository/MesutUluag/Portugese-for-Kids-Backend"

# 4. Grant the service account the roles needed to deploy
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$SA" --role="roles/artifactregistry.writer"

gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$SA" --role="roles/run.developer"

gcloud iam service-accounts add-iam-policy-binding $SA \
  --project=$PROJECT \
  --role=roles/iam.serviceAccountUser \
  --member="serviceAccount:$SA"

# 5. Get the provider name — paste this as the GCP_WORKLOAD_IDENTITY_PROVIDER secret
gcloud iam workload-identity-pools providers describe github-provider \
  --project=$PROJECT --location=global \
  --workload-identity-pool=github-pool \
  --format="value(name)"
```

> **Note:** `PORT` is reserved by Cloud Run and set automatically — do not include it in `--set-env-vars`.

---

## AI Behaviour

### Story generation

Story sentences are generated by `gemini-3.1-flash-lite` via the Google Cloud Agent Platform (Vertex AI). A dedicated system prompt is loaded per context at startup. The model is constrained to:

- Output **exactly one** A1-level European Portuguese sentence (4–8 words) per request
- Focus on realistic, natural conversational phrases for the selected scene
- Return **raw JSON only** — no markdown, lists, or explanations
- Avoid repeating patterns from the `conversationHistory`
- Generate a **logical reply** when `previousSentence` is provided
- Always end `imagePrompt` values with `"colorful cute kids illustration, storybook art, bright colors, simple background, no text"`

**LLM parameters:**

| Parameter | Value |
|---|---|
| Model | `gemini-3.1-flash-lite` |
| Temperature | `0.4` |
| Top-K | `40` |
| Top-P | `0.85` |
| Transport | gRPC |
| Endpoint | `aiplatform.us.rep.googleapis.com` |

### Image generation

Images are generated by `gemini-3.1-flash-lite-image` via the standard Gemini GenAI API. Raw bytes are JPEG-compressed at 60% quality and stored in the semantic cache. When Gemini is unavailable, Pollinations and Hugging Face are tried in order.

---

## Testing

Tests are split into three layers:

### Unit tests — `StoryControllerTest`

Fast, no network, no credentials. `ChatClient` and `RateLimitService` are mocked with Mockito. Run as part of every `./mvnw test` invocation.

| Test | What it verifies |
|---|---|
| `createStory_returnsValidJsonResponse` | Happy path — response deserialises to a valid `StoryPage` with all 6 fields |
| `createStory_restaurantContext_usesRestaurantPrompt` | Context routing — restaurant context produces a restaurant sentence |
| `createStory_unknownContext_defaultsToSchool` | Context fallback — unknown context silently falls back to school |
| `createStory_rateLimitExceeded_throwsDailyLimitExceededException` | Rate limit enforcement — exception propagates before any LLM call |
| `createStory_nullPrompt_stillCallsChatClient` | Null prompt is passed through to the `ChatClient` |

### Unit tests — `SemanticImageCacheTest`

Tests the TF-IDF vector construction, cosine similarity, LRU eviction, and cache hit/miss logic without any network calls.

### AI evaluator tests — `StoryEvaluatorTest`

Integration tests using the Spring AI `RelevancyEvaluator` and `FactCheckingEvaluator`. Make real calls to Vertex AI Gemini. Tagged `@Tag("ai-eval")` and excluded from normal CI via Maven Surefire.

| Test | Evaluator | What it verifies |
|---|---|---|
| `storyResponse_isRelevantToPrompt` | `RelevancyEvaluator` | Response is relevant to the user's topic; score ≥ 0.9 |
| `storyResponse_portugueseSentenceCompliesWithRules` | `FactCheckingEvaluator` (custom prompt) | Portuguese sentence is A1-level, school-appropriate, single sentence |
| `englishTranslation_isNotHallucinated` | `FactCheckingEvaluator` (default prompt) | English translation is faithful to the Portuguese sentence |
| `imagePrompt_describesTheSameSceneAsSentence` | `FactCheckingEvaluator` (default prompt) | Image prompt describes the same scene as the sentence |

Run AI evaluator tests explicitly:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  GOOGLE_CLOUD_PROJECT='your-gcp-project-id' \
  ./mvnw test -DskipAiEvalGroups="" -Dgroups="ai-eval"
```

---

## Frontend

The companion React + TypeScript frontend is in the [`Portugese for Kids`](../Practice%20Portugese%20for%20Kids/Portugese%20for%20Kids/README.md) repo. When this backend is unavailable, the frontend automatically falls back to its built-in curated template pages.
