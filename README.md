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

- `google/gemini-2.5-flash-lite@default`


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

The server is configured to bind only to localhost for safety:

- host: `127.0.0.1`
- port: `8081`

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

## Notes

- Do not hardcode credentials in code or commit them to git.
- For deployed environments, prefer managed Google Cloud service identities instead of local credential files.
- The frontend is expected to call this backend at `http://127.0.0.1:8081` during local development.
- Vertex AI usage may incur Google Cloud charges depending on your account and usage.
