# 🇵🇹 Portuguese for Kids — Backend

Spring Boot REST API powering AI-generated story sentences and illustrations for the [Portuguese for Kids](https://mesutuluag.github.io/Portugese-for-Kids) frontend app.

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Runtime |
| Spring Boot | 3.5.16 | Web framework |
| Spring AI | 1.1.8 | LLM integration |
| Google Vertex AI (Agent Platform) | gemini-3.1-flash-lite | Story sentence generation |
| Hugging Face (FLUX.1-schnell) | — | AI image generation |
| Maven | Wrapper included | Build tool |
| Docker | — | Containerisation & Cloud Run deployment |

---

## API Reference

### `POST /api/story`

Generate a school-themed A1-level Portuguese sentence for children.

**Request body**

```json
{
  "prompt": "Generate a short Portuguese sentence for kids"
}
```

**Response** `200 OK`

```json
{
  "content": "{\"pt\":\"Posso brincar contigo no recreio?\",\"en\":\"Can I play with you at recess?\",\"mainEmoji\":\"🙂\",\"bgLeft\":\"🏫\",\"bgRight\":\"⚽\",\"imagePrompt\":\"two children smiling and playing together in a sunny school playground, colorful cute kids illustration, storybook art, bright colors, simple background, no text\"}"
}
```

The `content` field is a JSON-serialised `StoryPage`:

| Field | Type | Description |
|---|---|---|
| `pt` | string | A1-level European Portuguese sentence |
| `en` | string | English translation |
| `mainEmoji` | string | Single emoji representing the subject |
| `bgLeft` | string | Left background decoration emoji |
| `bgRight` | string | Right background decoration emoji |
| `imagePrompt` | string | Image generation prompt (always ends with `"colorful cute kids illustration, storybook art, bright colors, simple background, no text"`) |

**Error** `429 Too Many Requests` — daily per-IP limit exceeded.

---

### `GET /api/image`

Generate an illustration from a text prompt using Hugging Face Stable Diffusion.

**Query parameters**

| Parameter | Required | Default | Description |
|---|---|---|---|
| `imagePrompt` | ✅ | — | Text description of the image |
| `steps` | ❌ | `10` | Number of diffusion inference steps |
| `width` | ❌ | `768` | Output image width in pixels |
| `height` | ❌ | `368` | Output image height in pixels |

**Example**

```
GET /api/image?imagePrompt=a+child+reading+in+a+classroom&steps=20&width=768&height=368
```

**Response** `200 OK` — binary JPEG image (`Content-Type: image/jpeg`)

**Errors**

- `429 Too Many Requests` — daily per-IP limit exceeded
- Hugging Face upstream errors are forwarded as-is

---

## Rate Limiting

Both endpoints share a **50 requests per IP address per calendar day** quota tracked in-memory. The counter resets when the server restarts.

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
| `HF_API_TOKEN` | ✅ | — | Hugging Face API token (for image generation) |
| `GOOGLE_CLOUD_LOCATION` | ❌ | `us` | Agent Platform multi-region location (`us` or `eu`) |
| `GOOGLE_CLOUD_API_ENDPOINT` | ❌ | `aiplatform.us.rep.googleapis.com` | Agent Platform gRPC endpoint |
| `SERVER_ADDRESS` | ❌ | `127.0.0.1` | Bind address (`0.0.0.0` for Cloud Run) |
| `PORT` | ❌ | `8081` | HTTP port (`8080` for Cloud Run) |

---

## Running Locally

### Prerequisites

- Java 21
- Maven (or use the included `./mvnw` wrapper)
- A GCP service account with the `roles/aiplatform.user` role
- A [Hugging Face](https://huggingface.co) account and API token

### Start the server

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export GOOGLE_CLOUD_PROJECT='your-gcp-project-id'
export GOOGLE_CLOUD_LOCATION='us'
export GOOGLE_APPLICATION_CREDENTIALS='/absolute/path/to/service-account.json'
export HF_API_TOKEN='hf_xxxxxxxxxxxxxxxxxxxx'

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

Story sentences are generated by `gemini-3.1-flash-lite` via the Google Cloud Agent Platform using a structured system prompt per context. The model is constrained to:

- Output **exactly one** A1-level European Portuguese sentence per request
- Focus on **school-related conversations** (first weeks of school): greetings, introductions, classroom questions, teacher commands, playground talk, feelings at school
- Return **raw JSON only** — no markdown, lists, or explanations
- Avoid repeating patterns across requests
- Always end `imagePrompt` values with `"colorful cute kids illustration, storybook art, bright colors, simple background, no text"`

**LLM parameters:**

| Parameter | Value |
|---|---|
| Model | `gemini-3.1-flash-lite` |
| Temperature | `0.4` |
| Top-K | `40` |
| Top-P | `0.85` |
| Frequency Penalty | `0.3` |
| Transport | gRPC |
| Endpoint | `aiplatform.us.rep.googleapis.com` |

---

## Testing

Tests are split into two layers:

### Unit tests — `StoryControllerTest`

Fast, no network, no credentials. The `ChatClient` and `RateLimitService` are mocked with Mockito. Run as part of every `./mvnw test` invocation.

| Test | What it verifies |
|---|---|
| `createStory_returnsValidJsonResponse` | Happy path — response deserialises to a valid `StoryPage` with all 6 fields |
| `createStory_rateLimitExceeded_throwsDailyLimitExceededException` | Rate limit enforcement — exception propagates before any LLM call |
| `createStory_nullPrompt_stillCallsChatClient` | Null prompt is passed through to the `ChatClient` |

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

## Project Structure

```
src/main/java/com/mesutuluag/portugeseforkidsbackend/
├── PortugeseForKidsBackendApplication.java  # Spring Boot entry point
├── StoryController.java                     # POST /api/story
├── ImageController.java                     # GET /api/image
├── StoryRequest.java                        # Request DTO
├── StoryResponse.java                       # Response DTO
├── StoryPage.java                           # Story data record
├── RateLimitService.java                    # Per-IP daily rate limiting
└── DailyLimitExceededException.java         # Custom 429 exception

src/main/resources/
├── application.properties                   # Spring & AI configuration
└── prompts/story-system-prompt.md           # LLM system prompt

src/test/java/com/mesutuluag/portugeseforkidsbackend/
├── StoryControllerTest.java                 # Unit tests (mocked, no network)
└── StoryEvaluatorTest.java                  # AI evaluator tests (@Tag("ai-eval"))
```

---

## Frontend

The companion React + TypeScript frontend is in the [`Portugese for Kids`](../Practice%20Portugese%20for%20Kids/Portugese%20for%20Kids/README.md) repo. When this backend is unavailable, the frontend automatically falls back to its built-in curated template pages.
