// SummaryMlClient.java
package com.bdreview.platform.summary;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** REST client to the Python ML microservice's `/review-summary/generate` (LangGraph pipeline, §15). */
@Component
public class SummaryMlClient {

    private final WebClient webClient;

    public SummaryMlClient(WebClient mlServiceWebClient) {
        this.webClient = mlServiceWebClient;
    }

    public Mono<SummaryGenerationResponseDto> generate(SummaryGenerationRequestDto request) {
        return webClient.post()
                .uri("/review-summary/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SummaryGenerationResponseDto.class);
    }
}