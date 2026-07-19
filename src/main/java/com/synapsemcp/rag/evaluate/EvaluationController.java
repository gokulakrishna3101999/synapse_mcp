package com.synapsemcp.rag.evaluate;

import com.synapsemcp.common.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * rag_plan.md Stage 6c. Same tenant-key-only, opaque-{@code id} ownership pattern as Stage 6a/6b.
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{knowledgebaseId}/evaluate")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public EvaluateResponse evaluate(
            @PathVariable UUID knowledgebaseId, @Valid @RequestBody EvaluateRequest request) {
        return evaluationService.evaluate(TenantContext.get(), knowledgebaseId, request);
    }
}
