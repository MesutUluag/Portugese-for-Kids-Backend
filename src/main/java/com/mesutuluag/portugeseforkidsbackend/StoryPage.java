package com.mesutuluag.portugeseforkidsbackend;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record StoryPage(
		@JsonProperty("pt")
		@JsonPropertyDescription("A1-level European Portuguese sentence a child would say or hear at school")
		String pt,

		@JsonProperty("en")
		@JsonPropertyDescription("Natural English translation of the Portuguese sentence")
		String en,

		@JsonProperty("mainEmoji")
		@JsonPropertyDescription("One emoji representing the main character or subject of the sentence")
		String mainEmoji,

		@JsonProperty("bgLeft")
		@JsonPropertyDescription("One emoji for the left side background decoration that fits the scene")
		String bgLeft,

		@JsonProperty("bgRight")
		@JsonPropertyDescription("One emoji for the right side background decoration that fits the scene")
		String bgRight,

		@JsonProperty("imagePrompt")
		@JsonPropertyDescription("Image generation prompt for this scene. Must describe a concrete visual: who is doing what, where. Must always end with: colorful cute kids illustration, storybook art, bright colors, simple background, no text. Example for 'Good morning teacher': a smiling child waving at a teacher in a sunny classroom, colorful cute kids illustration, storybook art, bright colors, simple background, no text")
		String imagePrompt
) {}
