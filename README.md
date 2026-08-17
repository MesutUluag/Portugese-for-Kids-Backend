# Portugese for Kids Backend

Spring Boot backend for AI story generation used by the frontend app.

## Tech Stack

- Spring Boot 3.5.7
- Spring AI 1.0.3
- Vertex AI Gemini
- Java 17
- Maven Wrapper

## Configuration

The backend uses Google Cloud Vertex AI with Gemini.

Current configured model:

- `gemini-2.5-flash-lite`


Required environment variables:

```bash
export GOOGLE_CLOUD_PROJECT='your-google-cloud-project-id'
export GOOGLE_CLOUD_LOCATION='us-central1'
```

Authentication must be provided with Google Cloud Application Default Credentials or a service account credentials file.

Example using a local service account file:

```bash
export GOOGLE_APPLICATION_CREDENTIALS='/absolute/path/to/service-account.json'
```

The server uses environment-based binding:

- local default host: `127.0.0.1`
- local default port: `8081`
- Cloud Run host override: `0.0.0.0`
- Cloud Run port override: `8080`

See [`application.properties`](src/main/resources/application.properties).

## Run locally

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export GOOGLE_CLOUD_PROJECT='your-google-cloud-project-id'
export GOOGLE_CLOUD_LOCATION='us-central1'
export GOOGLE_APPLICATION_CREDENTIALS='/absolute/path/to/service-account.json'
./mvnw spring-boot:run
```

## Test

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw test
```

## API

### `POST /api/story`

Request body:

```json
{ "prompt": "Generate a short Portuguese sentence for kids" }
```

Response body:

```json
{ "content": "{\"pt\":\"O gato dorme no sofá.\",\"en\":\"The cat sleeps on the sofa.\",\"mainEmoji\":\"🐱\",\"bgLeft\":\"🛋️\",\"bgRight\":\"😴\"}" }
```

## Deploy to Cloud Run

Build the jar locally:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw clean package
```

Build and push the container with Google Cloud Build:

```bash
gcloud builds submit --tag gcr.io/$GOOGLE_CLOUD_PROJECT/portugese-for-kids-backend
```

Create a runtime service account with Vertex AI access if you do not already have one:

```bash
gcloud iam service-accounts create portugese-for-kids-backend

gcloud projects add-iam-policy-binding $GOOGLE_CLOUD_PROJECT \
  --member="serviceAccount:portugese-for-kids-backend@$GOOGLE_CLOUD_PROJECT.iam.gserviceaccount.com" \
  --role="roles/aiplatform.user"
```

Deploy to Cloud Run:

```bash
gcloud run deploy portugese-for-kids-backend \
  --image gcr.io/$GOOGLE_CLOUD_PROJECT/portugese-for-kids-backend \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --service-account portugese-for-kids-backend@$GOOGLE_CLOUD_PROJECT.iam.gserviceaccount.com \
  --set-env-vars GOOGLE_CLOUD_PROJECT=$GOOGLE_CLOUD_PROJECT,GOOGLE_CLOUD_LOCATION=us-central1,SERVER_ADDRESS=0.0.0.0,PORT=8080
```

## Notes

- Do not hardcode credentials in code or commit them to git.
- For deployed environments, prefer managed Google Cloud service identities instead of local credential files.
- The frontend is expected to call this backend at `http://127.0.0.1:8081` during local development.
- Update CORS before production so the deployed frontend origin is allowed.
- Vertex AI usage may incur Google Cloud charges depending on your account and usage.
