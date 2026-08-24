# 🇵🇹 Portuguese for Kids — Backend

Spring Boot REST API powering AI-generated story sentences and illustrations for the [Portuguese for Kids](https://mesutuluag.github.io/Portugese-for-Kids) frontend app.

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Runtime |
| Spring Boot | 3.5.16 | Web framework |
| Spring AI | 1.1.8 | LLM integration |
| Google Vertex AI | gemini-2.5-flash-lite | Story sentence generation |
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
| `GOOGLE_CLOUD_LOCATION` | ❌ | `us-central1` | Vertex AI region |
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
export GOOGLE_CLOUD_LOCATION='us-central1'
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
  -e GOOGLE_CLOUD_LOCATION='us-central1' \
  -e GOOGLE_APPLICATION_CREDENTIALS='/app/service-account.json' \
  -e HF_API_TOKEN='hf_xxxxxxxxxxxxxxxxxxxx' \
  -e SERVER_ADDRESS='0.0.0.0' \
  -e PORT='8080' \
  portugese-for-kids-backend
```

---

## Deploy to Google Cloud Run

```bash
# 1. Build and push the container image
gcloud builds submit --tag gcr.io/$GOOGLE_CLOUD_PROJECT/portugese-for-kids-backend

# 2. Create a service account with Vertex AI permissions
gcloud iam service-accounts create portugese-for-kids-backend
gcloud projects add-iam-policy-binding $GOOGLE_CLOUD_PROJECT \
  --member="serviceAccount:portugese-for-kids-backend@$GOOGLE_CLOUD_PROJECT.iam.gserviceaccount.com" \
  --role="roles/aiplatform.user"

# 3. Deploy
gcloud run deploy portugese-for-kids-backend \
  --image gcr.io/$GOOGLE_CLOUD_PROJECT/portugese-for-kids-backend \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --service-account portugese-for-kids-backend@$GOOGLE_CLOUD_PROJECT.iam.gserviceaccount.com \
  --set-env-vars GOOGLE_CLOUD_PROJECT=$GOOGLE_CLOUD_PROJECT,GOOGLE_CLOUD_LOCATION=us-central1,SERVER_ADDRESS=0.0.0.0,PORT=8080
```

> **Note:** Hugging Face token and GCP credentials must be supplied as Cloud Run secrets, not plain environment variables, in production.

---

## AI Behaviour

Story sentences are generated by `gemini-2.5-flash-lite` using a structured system prompt (`src/main/resources/prompts/story-system-prompt.md`). The model is constrained to:

- Output **exactly one** A1-level European Portuguese sentence per request
- Focus on **school-related conversations** (first weeks of school): greetings, introductions, classroom questions, teacher commands, playground talk, feelings at school
- Return **raw JSON only** — no markdown, lists, or explanations
- Avoid repeating patterns across requests
- Always end `imagePrompt` values with `"colorful cute kids illustration, storybook art, bright colors, simple background, no text"`

**LLM parameters:**

| Parameter | Value |
|---|---|
| Model | `gemini-2.5-flash-lite` |
| Temperature | `0.9` |
| Top-K | `64` |
| Top-P | `0.98` |
| Frequency Penalty | `0.5` |
| Transport | gRPC |

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
