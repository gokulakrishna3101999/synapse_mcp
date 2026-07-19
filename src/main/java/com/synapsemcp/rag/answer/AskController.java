package com.synapsemcp.rag.answer;

import com.synapsemcp.common.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * rag_plan.md Stage 6b. Tenant-key-only, same ownership pattern as {@link
 * com.synapsemcp.rag.retrieve.SearchController} - the knowledge_base is identified by its own
 * opaque {@code id}, ownership enforced inside {@link RagAnsweringService} (via {@code
 * HybridRetrievalService}'s own 404-on-mismatch check).
 *
 * <p>JSON vs. SSE is selected by ordinary Spring MVC content negotiation on the client's {@code
 * Accept} header - two {@code @PostMapping}s on the identical path, differentiated only by {@code
 * produces} (rag_plan.md: "controlled by a request parameter or Accept header"). {@code
 * TenantContext.get()} is read synchronously on the servlet thread, before the reactive pipeline is
 * even constructed, and passed through as a plain method parameter from there - the SSE path never
 * touches the request-scoped {@code ThreadLocal} from within the {@link Flux} itself, which could
 * otherwise run its later emissions on a different thread.
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{knowledgebaseId}/ask")
public class AskController {

    private final RagAnsweringService ragAnsweringService;

    public AskController(RagAnsweringService ragAnsweringService) {
        this.ragAnsweringService = ragAnsweringService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse ask(
            @PathVariable UUID knowledgebaseId, @Valid @RequestBody AskRequest request) {
        return ragAnsweringService.ask(TenantContext.get(), knowledgebaseId, request);
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(
            @PathVariable UUID knowledgebaseId, @Valid @RequestBody AskRequest request) {
        UUID tenantId = TenantContext.get();
        return ragAnsweringService.askStream(tenantId, knowledgebaseId, request);
    }
}
