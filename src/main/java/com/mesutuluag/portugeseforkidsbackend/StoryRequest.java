package com.mesutuluag.portugeseforkidsbackend;

public final class StoryRequest {

	private String prompt;
	private String context;

	public StoryRequest() {
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getContext() {
		return context;
	}

	public void setContext(String context) {
		this.context = context;
	}
}
