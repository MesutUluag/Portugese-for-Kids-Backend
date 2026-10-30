package com.mesutuluag.portugeseforkidsbackend.story;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/story")
public class StoryController {

    private final RateLimitService rateLimitService;
    private final StoryService storyService;

    public StoryController(RateLimitService rateLimitService, StoryService storyService) {
        this.rateLimitService = rateLimitService;
        this.storyService = storyService;
    }

    @PostMapping
    public StoryResponse createStory(
            @RequestBody(required = false) StoryRequest request,
            HttpServletRequest httpServletRequest) {
        rateLimitService.checkAndIncrement(RateLimitService.resolveClientIp(httpServletRequest));
        return storyService.generateStory(request);
    }
}
