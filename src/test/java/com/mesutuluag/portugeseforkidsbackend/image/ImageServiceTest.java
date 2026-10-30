package com.mesutuluag.portugeseforkidsbackend.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private SemanticImageCache semanticImageCache;

    @Mock
    private GeminiImageClient geminiImageClient;

    @Mock
    private PollinationsImageClient pollinationsImageClient;

    private ImageService imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageService(
                semanticImageCache,
                geminiImageClient,
                pollinationsImageClient
        );
    }

    private byte[] createTestImage() throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void generateJpeg_cacheHit_returnsCachedWithoutCallingProviders() {
        byte[] cachedImage = new byte[]{1, 2, 3};
        when(semanticImageCache.get("classroom")).thenReturn(cachedImage);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isSameAs(cachedImage);
        verifyNoInteractions(geminiImageClient, pollinationsImageClient);
    }

    @Test
    void generateJpeg_geminiDisabled_skipsGeminiAndFallsThroughToPollinations() {
        byte[] pollinationsImage = new byte[]{4, 5, 6};
        when(semanticImageCache.get("classroom")).thenReturn(null);
        when(geminiImageClient.isEnabled()).thenReturn(false);
        when(pollinationsImageClient.generate("classroom", 512, 512)).thenReturn(pollinationsImage);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isSameAs(pollinationsImage);
        verify(geminiImageClient, never()).generate(anyString());
        verify(pollinationsImageClient).generate("classroom", 512, 512);
    }

    @Test
    void generateJpeg_allProvidersFail_returnsNull() {
        when(semanticImageCache.get("classroom")).thenReturn(null);
        when(geminiImageClient.isEnabled()).thenReturn(false);
        when(pollinationsImageClient.generate("classroom", 512, 512)).thenReturn(null);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isNull();
    }

    @Test
    void generateJpeg_geminiEnabled_success_convertsToJpegAndCaches() throws Exception {
        byte[] rawImage = createTestImage();
        when(semanticImageCache.get("classroom")).thenReturn(null);
        when(geminiImageClient.isEnabled()).thenReturn(true);
        when(geminiImageClient.generate("classroom")).thenReturn(rawImage);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isNotNull();
        verify(semanticImageCache).put(eq("classroom"), any(byte[].class));
        verifyNoInteractions(pollinationsImageClient);
    }

    @Test
    void generateJpeg_geminiEnabled_returnsNull_fallsThroughToPollinations() {
        byte[] pollinationsImage = new byte[]{10, 11, 12};
        when(semanticImageCache.get("classroom")).thenReturn(null);
        when(geminiImageClient.isEnabled()).thenReturn(true);
        when(geminiImageClient.generate("classroom")).thenReturn(null);
        when(pollinationsImageClient.generate("classroom", 512, 512)).thenReturn(pollinationsImage);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isSameAs(pollinationsImage);
        verify(pollinationsImageClient).generate("classroom", 512, 512);
    }

    @Test
    void generateJpeg_geminiEnabled_throwsException_fallsThroughToPollinations() {
        byte[] pollinationsImage = new byte[]{13, 14, 15};
        when(semanticImageCache.get("classroom")).thenReturn(null);
        when(geminiImageClient.isEnabled()).thenReturn(true);
        when(geminiImageClient.generate("classroom")).thenThrow(new RuntimeException("API error"));
        when(pollinationsImageClient.generate("classroom", 512, 512)).thenReturn(pollinationsImage);

        byte[] result = imageService.generateJpeg("classroom", 512, 512);

        assertThat(result).isSameAs(pollinationsImage);
        verify(pollinationsImageClient).generate("classroom", 512, 512);
    }
}
