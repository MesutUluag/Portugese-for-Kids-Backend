package com.mesutuluag.portugeseforkidsbackend.image;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiImageClientTest {

    @Test
    void whenDisabled_isEnabledReturnsFalseAndGenerateReturnsNull() {
        GeminiImageClient client = new GeminiImageClient("some-api-key", false);

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generate("a child in a classroom")).isNull();
    }

    @Test
    void whenApiKeyBlank_isEnabledReturnsFalseAndGenerateReturnsNull() {
        GeminiImageClient client = new GeminiImageClient("", true);

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generate("a child in a classroom")).isNull();
    }

    @Test
    void whenApiKeyNull_isEnabledReturnsFalseAndGenerateReturnsNull() {
        GeminiImageClient client = new GeminiImageClient(null, true);

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generate("a child in a classroom")).isNull();
    }

    @Test
    void whenDisabledWithBlankKey_isEnabledReturnsFalse() {
        GeminiImageClient client = new GeminiImageClient("", false);

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generate("a child in a classroom")).isNull();
    }

    @Test
    void whenEnabledWithValidKey_isEnabledReturnsTrue() {
        GeminiImageClient client = new GeminiImageClient("AIzaSyFakeKeyForTesting1234567890", true);

        assertThat(client.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_whenCooldownActive_returnsFalse() {
        GeminiImageClient client = new GeminiImageClient("AIzaSyFakeKeyForTesting1234567890", true);
        client.setCooldownUntil(Instant.now().plus(Duration.ofMinutes(10)));

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generate("a child in a classroom")).isNull();
    }

    @Test
    void isEnabled_whenCooldownExpired_returnsTrueAndClearsCooldown() {
        GeminiImageClient client = new GeminiImageClient("AIzaSyFakeKeyForTesting1234567890", true);
        client.setCooldownUntil(Instant.now().minus(Duration.ofSeconds(1)));

        assertThat(client.isEnabled()).isTrue();
        assertThat(client.getCooldownUntil()).isNull();
    }
}
