package com.mesutuluag.portugeseforkidsbackend;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI evaluator tests for POST /api/story.
 *
 * These tests make real calls to Vertex AI Gemini and require:
 *   - GOOGLE_CLOUD_PROJECT env var
 *   - GOOGLE_APPLICATION_CREDENTIALS env var (path to GCP service-account JSON)
 *
 * They are excluded from normal CI via the Maven Surefire {@code <excludedGroups>ai-eval</excludedGroups>}
 * configuration. Run them explicitly with:
 *   {@code ./mvnw test -Dgroups="ai-eval"}
 */
@Tag("ai-eval")
@SpringBootTest
class StoryEvaluatorTest {

    /** Minimum acceptable relevancy score (0.0–1.0). */
    private static final float MIN_RELEVANCY_SCORE = 0.9f;

    /**
     * Custom evaluation prompt for compliance checking.
     *
     * The default FactCheckingEvaluator prompt is designed for factual grounding
     * ("is this claim supported by this document?"). That is a poor fit here because
     * the system prompt describes *output rules*, not a factual document.
     *
     * This prompt rephrases the task as rule-compliance evaluation:
     * - Does the Portuguese sentence sound like something a child would say at school?
     * - Is it A1-level (short, simple vocabulary)?
     * - Does the English translation match the Portuguese sentence?
     *
     * The {document} placeholder receives the distilled compliance rules.
     * The {claim} placeholder receives only the Portuguese sentence extracted from
     * the JSON (not the raw JSON blob, which confuses the evaluator LLM).
     */
    private static final String COMPLIANCE_PROMPT = """
            You are evaluating whether a generated Portuguese sentence for children complies with the following rules.

            Rules:
            {document}

            Generated Portuguese sentence:
            {claim}

            Does the sentence comply with all the rules above?
            Answer only "yes" or "no".
            """;

    /**
     * Focused compliance rules extracted from the system prompt.
     * Distilled to the three most objectively checkable constraints.
     */
    private static final String COMPLIANCE_RULES = """
            1. The sentence must be A1-level European Portuguese — short, simple vocabulary, suitable for a child.
            2. The sentence must be something a child would realistically say or hear at a school setting \
            (greetings, classroom questions, playground talk, teacher commands, etc.).
            3. The sentence must be a single sentence, not a list or paragraph.
            """;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private StoryController storyController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RelevancyEvaluator relevancyEvaluator;
    private FactCheckingEvaluator complianceEvaluator;
    /** Uses the default FactCheckingEvaluator prompt — suited for factual grounding checks. */
    private FactCheckingEvaluator hallucinationEvaluator;

    @BeforeEach
    void setUp() {
        relevancyEvaluator = new RelevancyEvaluator(chatClientBuilder);
        complianceEvaluator = FactCheckingEvaluator.builder(chatClientBuilder)
                .evaluationPrompt(COMPLIANCE_PROMPT)
                .build();
        hallucinationEvaluator = FactCheckingEvaluator.builder(chatClientBuilder).build();
    }

    // -------------------------------------------------------------------------
    // Relevancy: the story response must be relevant to the user's prompt
    // -------------------------------------------------------------------------
    @Test
    void storyResponse_isRelevantToPrompt() throws Exception {
        String userPrompt = "greet a teacher";
        StoryRequest request = new StoryRequest();
        request.setPrompt(userPrompt);

        StoryResponse storyResponse = storyController.createStory(request, new MockHttpServletRequest());

        // Two-arg constructor: no context documents needed for relevancy check
        EvaluationRequest evalRequest = new EvaluationRequest(
                userPrompt,
                storyResponse.getContent()
        );

        EvaluationResponse evalResponse = relevancyEvaluator.evaluate(evalRequest);

        assertThat(evalResponse.isPass())
                .as("Story response should be relevant to the user prompt '%s'", userPrompt)
                .isTrue();
        assertThat(evalResponse.getScore())
                .as("Relevancy score should be >= %.1f (got %.1f)", MIN_RELEVANCY_SCORE, evalResponse.getScore())
                .isGreaterThanOrEqualTo(MIN_RELEVANCY_SCORE);
    }

    // -------------------------------------------------------------------------
    // Compliance: the Portuguese sentence must satisfy the system prompt rules
    // -------------------------------------------------------------------------
    @Test
    void storyResponse_portugueseSentenceCompliesWithRules() throws Exception {
        String userPrompt = "playground conversation";
        StoryRequest request = new StoryRequest();
        request.setPrompt(userPrompt);

        StoryResponse storyResponse = storyController.createStory(request, new MockHttpServletRequest());

        // Extract only the Portuguese sentence as the claim — passing raw JSON
        // confuses the evaluator LLM. The distilled COMPLIANCE_RULES serve as
        // the reference document instead of the full 44-line system prompt.
        StoryPage page = objectMapper.readValue(storyResponse.getContent(), StoryPage.class);
        String portugueseSentence = page.pt();

        List<Document> rules = List.of(new Document(COMPLIANCE_RULES));
        EvaluationRequest evalRequest = new EvaluationRequest(
                userPrompt,
                rules,
                portugueseSentence
        );

        EvaluationResponse evalResponse = complianceEvaluator.evaluate(evalRequest);

        assertThat(evalResponse.isPass())
                .as("Portuguese sentence '%s' should comply with the system prompt rules", portugueseSentence)
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Hallucination — English translation must match the Portuguese sentence
    // -------------------------------------------------------------------------
    @Test
    void englishTranslation_isNotHallucinated() throws Exception {
        StoryRequest request = new StoryRequest();
        request.setPrompt("introduce yourself to a classmate");

        StoryResponse storyResponse = storyController.createStory(request, new MockHttpServletRequest());
        StoryPage page = objectMapper.readValue(storyResponse.getContent(), StoryPage.class);

        // Document: the Portuguese sentence (ground truth).
        // Claim: the asserted English translation. If the model hallucinated a
        // translation that doesn't match, the evaluator should return "no".
        List<Document> ptDocument = List.of(new Document(
                "Portuguese sentence: " + page.pt()
        ));
        String translationClaim = "The English translation of the sentence is: " + page.en();

        EvaluationRequest evalRequest = new EvaluationRequest(
                page.pt(),
                ptDocument,
                translationClaim
        );

        EvaluationResponse evalResponse = hallucinationEvaluator.evaluate(evalRequest);

        assertThat(evalResponse.isPass())
                .as("English translation '%s' should be supported by the Portuguese sentence '%s'",
                        page.en(), page.pt())
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Hallucination — image prompt must describe the same scene as the sentence
    // -------------------------------------------------------------------------
    @Test
    void imagePrompt_describesTheSameSceneAsSentence() throws Exception {
        StoryRequest request = new StoryRequest();
        request.setPrompt("ask where the notebook is");

        StoryResponse storyResponse = storyController.createStory(request, new MockHttpServletRequest());
        StoryPage page = objectMapper.readValue(storyResponse.getContent(), StoryPage.class);

        // Document: the sentence in both languages (gives the evaluator full context).
        // Claim: the image prompt. If it describes an unrelated scene the evaluator
        // returns "no", catching hallucinated or copy-pasted image descriptions.
        List<Document> sentenceDocument = List.of(new Document(
                "Portuguese: " + page.pt() + "\nEnglish: " + page.en()
        ));
        String imagePromptClaim = "The image shows: " + page.imagePrompt();

        EvaluationRequest evalRequest = new EvaluationRequest(
                page.pt(),
                sentenceDocument,
                imagePromptClaim
        );

        EvaluationResponse evalResponse = hallucinationEvaluator.evaluate(evalRequest);

        assertThat(evalResponse.isPass())
                .as("Image prompt '%s' should describe the same scene as '%s'",
                        page.imagePrompt(), page.pt())
                .isTrue();
    }
}
