package org.vaadin.marcus.enterpriseai.advisors;

import com.cohere.api.Cohere;
import com.cohere.api.resources.v2.requests.V2RerankRequest;
import com.cohere.api.resources.v2.types.V2RerankRequestDocumentsItem;
import com.cohere.api.resources.v2.types.V2RerankResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.document.Document;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Context for the question is retrieved from a Vector Store, reranked using Cohere,
 * and filtered based on relevance score before being added to the prompt's user text.
 */
public class RerankQuestionAnswerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final String DEFAULT_USER_TEXT_ADVISE = """
            Context information is below.
            ---------------------
            {question_answer_context}
            ---------------------
            Given the context information above, and not prior knowledge,
            answer the question. If you cannot find the answer in the context, 
            inform the user that you don't have enough information to answer.
            """;

    private static final int DEFAULT_ORDER = 0;
    private static final float DEFAULT_RELEVANCY_THRESHOLD = 0.7f;
    private static final String DEFAULT_MODEL = "rerank-english-v3.0";

    private final List<DataSource> dataSources;
    private final Cohere cohere;
    private final String userTextAdvise;
    private final boolean protectFromBlocking;
    private final int order;
    private final String model;
    private final float relevancyThreshold;

    public static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";
    public static final String FILTER_EXPRESSION = "qa_filter_expression";
    public static final String RELEVANCY_THRESHOLD_PARAM = "relevancy_threshold";

    private RerankQuestionAnswerAdvisor(List<DataSource> dataSources, String cohereApiKey,
                                        String userTextAdvise, boolean protectFromBlocking,
                                        int order, String model, float relevancyThreshold) {
        Assert.notEmpty(dataSources, "At least one DataSource must be provided");
        Assert.hasText(cohereApiKey, "Cohere API key must not be empty");
        Assert.hasText(userTextAdvise, "UserTextAdvise must not be empty");

        this.dataSources = dataSources;
        this.cohere = Cohere.builder()
            .token(cohereApiKey)
            .clientName("spring-ai-rerank")
            .build();
        this.userTextAdvise = userTextAdvise;
        this.protectFromBlocking = protectFromBlocking;
        this.order = order;
        this.model = model;
        this.relevancyThreshold = relevancyThreshold;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        AdvisedRequest advisedRequest2 = before(advisedRequest);
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest2);
        return after(advisedResponse);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        Flux<AdvisedResponse> advisedResponses = (this.protectFromBlocking)
            ? Mono.just(advisedRequest)
            .publishOn(Schedulers.boundedElastic())
            .map(this::before)
            .flatMapMany(chain::nextAroundStream)
            : chain.nextAroundStream(before(advisedRequest));

        return advisedResponses.map(ar -> {
            if (onFinishReason().test(ar)) {
                ar = after(ar);
            }
            return ar;
        });
    }

    private AdvisedRequest before(AdvisedRequest request) {
        var context = new HashMap<>(request.adviseContext());

        // Get threshold from parameters or use default
        float threshold = context.containsKey(RELEVANCY_THRESHOLD_PARAM)
            ? Float.parseFloat(context.get(RELEVANCY_THRESHOLD_PARAM).toString())
            : this.relevancyThreshold;

        String filterExpression = context.containsKey(FILTER_EXPRESSION) ?
            context.get(FILTER_EXPRESSION).toString() : null;

        // Search all data sources in parallel and collect results
        List<Document> documents = Flux.fromIterable(dataSources)
            .flatMap(source -> source.search(request.userText(), filterExpression))
            .collectList()
            .block(); // Safe to block here as we're already in a blocking context

        System.out.println("Question: " + request.userText());
        System.out.println("Found " + documents.size() + " documents across all sources");

        // Convert documents for reranking
        List<V2RerankRequestDocumentsItem> rerankDocs = documents.stream()
            .map(doc -> V2RerankRequestDocumentsItem.of(doc.getContent()))
            .collect(Collectors.toList());

        // Rerank documents using Cohere
        V2RerankResponse rerankResponse = cohere.v2().rerank(
            V2RerankRequest.builder()
                .model(this.model)
                .query(request.userText())
                .documents(rerankDocs)
                .build()
        );

        // Filter documents based on relevance score
        List<Document> relevantDocs = rerankResponse.getResults().stream()
            .filter(result -> result.getRelevanceScore() >= threshold)
            .map(result -> documents.get(result.getIndex()))
            .collect(Collectors.toList());

        System.out.println("Filtered to " + relevantDocs.size() + " relevant documents");

        context.put(RETRIEVED_DOCUMENTS, relevantDocs);

        // Create context from relevant documents
        String documentContext = relevantDocs.stream()
            .map(Document::getContent)
            .collect(Collectors.joining(System.lineSeparator()));

        // Update user parameters
        Map<String, Object> advisedUserParams = new HashMap<>(request.userParams());
        advisedUserParams.put("question_answer_context", documentContext);

        // Create the advised request
        String advisedUserText = request.userText() + System.lineSeparator() + this.userTextAdvise;

        return AdvisedRequest.from(request)
            .withUserText(advisedUserText)
            .withUserParams(advisedUserParams)
            .withAdviseContext(context)
            .build();
    }

    private AdvisedResponse after(AdvisedResponse advisedResponse) {
        var chatResponseBuilder = org.springframework.ai.chat.model.ChatResponse.builder()
            .from(advisedResponse.response());
        chatResponseBuilder.withMetadata(RETRIEVED_DOCUMENTS,
            advisedResponse.adviseContext().get(RETRIEVED_DOCUMENTS));
        return new AdvisedResponse(chatResponseBuilder.build(), advisedResponse.adviseContext());
    }

    private Predicate<AdvisedResponse> onFinishReason() {
        return (advisedResponse) -> advisedResponse.response()
            .getResults()
            .stream()
            .anyMatch(result -> result != null && result.getMetadata() != null
                && StringUtils.hasText(result.getMetadata().getFinishReason()));
    }

    public static Builder builder(List<DataSource> dataSources, String cohereApiKey) {
        return new Builder(dataSources, cohereApiKey);
    }

    public static class Builder {
        private final List<DataSource> dataSources;
        private final String cohereApiKey;
        private String userTextAdvise = DEFAULT_USER_TEXT_ADVISE;
        private boolean protectFromBlocking = true;
        private int order = DEFAULT_ORDER;
        private String model = DEFAULT_MODEL;
        private float relevancyThreshold = DEFAULT_RELEVANCY_THRESHOLD;

        private Builder(List<DataSource> dataSources, String cohereApiKey) {
            Assert.notEmpty(dataSources, "At least one DataSource must be provided");
            Assert.hasText(cohereApiKey, "Cohere API key must not be empty");
            this.dataSources = new ArrayList<>(dataSources);
            this.cohereApiKey = cohereApiKey;
        }

        public Builder withUserTextAdvise(String userTextAdvise) {
            Assert.hasText(userTextAdvise, "UserTextAdvise must not be empty");
            this.userTextAdvise = userTextAdvise;
            return this;
        }

        public Builder withProtectFromBlocking(boolean protectFromBlocking) {
            this.protectFromBlocking = protectFromBlocking;
            return this;
        }

        public Builder withOrder(int order) {
            this.order = order;
            return this;
        }

        public Builder withModel(String model) {
            Assert.hasText(model, "Model must not be empty");
            this.model = model;
            return this;
        }

        public Builder withRelevancyThreshold(float threshold) {
            Assert.isTrue(threshold >= 0 && threshold <= 1,
                "Relevancy threshold must be between 0 and 1");
            this.relevancyThreshold = threshold;
            return this;
        }

        public RerankQuestionAnswerAdvisor build() {
            return new RerankQuestionAnswerAdvisor(
                this.dataSources,
                this.cohereApiKey,
                this.userTextAdvise,
                this.protectFromBlocking,
                this.order,
                this.model,
                this.relevancyThreshold
            );
        }
    }
}