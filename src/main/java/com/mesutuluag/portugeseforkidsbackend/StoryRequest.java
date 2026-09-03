package com.mesutuluag.portugeseforkidsbackend;

public final class StoryRequest {

	private String prompt;
	private String context;
	private String previousSentence;

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

	public String getPreviousSentence() {
		return previousSentence;
	}

	public void setPreviousSentence(String previousSentence) {
		this.previousSentence = previousSentence;
	}
}
