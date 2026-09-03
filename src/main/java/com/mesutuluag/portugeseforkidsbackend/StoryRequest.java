package com.mesutuluag.portugeseforkidsbackend;

import java.util.List;

public final class StoryRequest {

	private String prompt;
	private String context;
	private String previousSentence;
	private List<String> conversationHistory;

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

	public List<String> getConversationHistory() {
		return conversationHistory;
	}

	public void setConversationHistory(List<String> conversationHistory) {
		this.conversationHistory = conversationHistory;
	}
}
