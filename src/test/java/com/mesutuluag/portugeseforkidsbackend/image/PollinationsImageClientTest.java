package com.mesutuluag.portugeseforkidsbackend.image;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollinationsImageClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<byte[]> httpResponse;

    private PollinationsImageClient client;

    @BeforeEach
    void setUp() {
        client = new PollinationsImageClient(httpClient);
    }

    @Test
    void defaultConstructor_initializesClient() {
        PollinationsImageClient defaultClient = new PollinationsImageClient();
        assertThat(defaultClient).isNotNull();
    }

    @Test
    void generate_successfulResponse_returnsBytes() throws Exception {
        byte[] expected = new byte[]{10, 20, 30};
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(expected);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(httpResponse);

        byte[] result = client.generate("cute puppy playing", 768, 368);

        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofByteArray()));
        HttpRequest captured = requestCaptor.getValue();
        assertThat(captured.uri().toString()).contains("prompt/cute+puppy+playing");
        assertThat(captured.uri().toString()).contains("width=768");
        assertThat(captured.uri().toString()).contains("height=368");
        assertThat(captured.timeout()).isPresent();
    }

    @Test
    void generate_non200Status_returnsNull() throws Exception {
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenReturn(httpResponse);

        byte[] result = client.generate("sunny park", 768, 368);

        assertThat(result).isNull();
    }

    @Test
    void generate_timeoutException_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenThrow(new HttpTimeoutException("request timed out"));

        byte[] result = client.generate("rainy day", 768, 368);

        assertThat(result).isNull();
    }

    @Test
    void generate_ioException_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenThrow(new IOException("connection reset"));

        byte[] result = client.generate("city view", 768, 368);

        assertThat(result).isNull();
    }

    @Test
    void generate_interruptedException_restoresInterruptStatusAndReturnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofByteArray())))
                .thenThrow(new InterruptedException("interrupted"));

        byte[] result = client.generate("mountain peak", 768, 368);

        assertThat(result).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear interrupt status so subsequent tests are not affected
        Thread.interrupted();
    }

    @Test
    void generate_nullOrBlankPrompt_returnsNullWithoutNetworkCall() {
        assertThat(client.generate(null, 768, 368)).isNull();
        assertThat(client.generate("   ", 768, 368)).isNull();

        verifyNoInteractions(httpClient);
    }
}
