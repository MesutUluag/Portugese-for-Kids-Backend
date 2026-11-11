package com.mesutuluag.portugeseforkidsbackend.config;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import com.mesutuluag.portugeseforkidsbackend.image.ImageController;
import com.mesutuluag.portugeseforkidsbackend.image.ImageService;
import com.mesutuluag.portugeseforkidsbackend.story.StoryController;
import com.mesutuluag.portugeseforkidsbackend.story.StoryResponse;
import com.mesutuluag.portugeseforkidsbackend.story.StoryService;
import com.mesutuluag.portugeseforkidsbackend.user.UserController;
import com.mesutuluag.portugeseforkidsbackend.user.UserProfile;
import com.mesutuluag.portugeseforkidsbackend.user.UserStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {StoryController.class, ImageController.class, UserController.class})
@Import({SecurityConfig.class, CorsConfig.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private StoryService storyService;

    @MockitoBean
    private ImageService imageService;

    @MockitoBean
    private UserStatsService userStatsService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void unauthenticatedRequestToStory_returns401() throws Exception {
        mockMvc.perform(post("/api/story")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Valid authentication token required."));
    }

    @Test
    void unauthenticatedRequestToImage_returns401() throws Exception {
        mockMvc.perform(get("/api/image")
                .param("imagePrompt", "cat"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void unauthenticatedRequestToUserMe_returns401() throws Exception {
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void authenticatedRequestToUserMe_returns200() throws Exception {
        UserProfile profile = UserProfile.defaultFor("uid-123", "test@kid.com", "Maya");
        when(userStatsService.getOrCreateProfile("uid-123", "test@kid.com", "Maya")).thenReturn(profile);

        mockMvc.perform(get("/api/user/me")
                .with(jwt().jwt(j -> j.subject("uid-123")
                        .claim("email", "test@kid.com")
                        .claim("name", "Maya"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-123"))
                .andExpect(jsonPath("$.displayName").value("Maya"));
    }

    @Test
    void authenticatedRequestToStory_returns200() throws Exception {
        when(storyService.generateStory(any())).thenReturn(new StoryResponse("{\"pt\":\"Olá\"}"));

        mockMvc.perform(post("/api/story")
                .with(jwt().jwt(j -> j.subject("uid-123")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("{\"pt\":\"Olá\"}"));
    }

    @Test
    void corsOptionsPreflight_allowed() throws Exception {
        mockMvc.perform(options("/api/story")
                .header("Origin", "https://mesutuluag.github.io")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://mesutuluag.github.io"));
    }
}
