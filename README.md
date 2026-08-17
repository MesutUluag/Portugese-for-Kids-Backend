# Portugese for Kids Backend

Spring Boot backend for AI story generation used by the frontend app.

## Tech Stack

- Spring Boot 3.5.7
- Spring AI 1.0.3
- Java 17
- Maven Wrapper

## Configuration

The backend reads the OpenAI API key from an environment variable.

Required environment variable:

```bash
export OPENAI_API_KEY='your-real-openai-key'
```

The server is configured to bind only to localhost for safety:

- host: `127.0.0.1`
- port: `8081`

See [`application.properties`](src/main/resources/application.properties).

## Run locally

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export OPENAI_API_KEY='your-real-openai-key'
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

- Do not hardcode API keys in code or commit them to git.
- If OpenAI returns quota or billing errors, the backend request will fail until the account is funded or usage is available.
- The frontend is expected to call this backend at `http://127.0.0.1:8081` during local development.
