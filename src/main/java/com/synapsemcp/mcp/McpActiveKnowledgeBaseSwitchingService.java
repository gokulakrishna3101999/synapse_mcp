package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-requested (2026-07-22) {@code switch_knowledge_base} tool: lets an already-tenant-linked MCP
 * account freely switch its "active" knowledge base by name, closing a gap the user identified
 * directly - every other knowledge-base-scoped tool (ask/search/ingest/evaluate/
 * update_knowledge_base/delete_knowledge_base) only ever accepted an explicit id, with no way to
 * reference a knowledge base by name at all ({@code list_knowledge_bases} already returns names,
 * but nothing let a caller act on one directly without first parsing its id out of that list's
 * response).
 *
 * <p>Mirrors {@link McpTenantSwitchingService}'s own shape: persists a real, durable pointer
 * ({@code mcp_users.active_knowledge_base_id}) rather than anything session-scoped, so it survives
 * across MCP tool calls (and even across client reconnects) exactly like the tenant link itself.
 *
 * <p>User's explicit requirement - validate before allowing a switch, and do not duplicate
 * validation that already exists: ownership is checked here, at switch time, by scoping the name
 * lookup to the caller's own {@code TenantContext} ({@link
 * KnowledgeBaseRepository#findByNameIgnoreCaseAndTenant_Id}) - a name belonging to another tenant's
 * knowledge base is indistinguishable from a nonexistent one, the same "not yours == doesn't exist"
 * contract every other knowledge-base lookup in this codebase already uses. Every tool that later
 * reads the active pointer instead of an explicit id (via {@link
 * McpToolInputs#resolveKnowledgeBaseId}) passes the exact same resolved UUID through the exact same
 * pre-existing {@code requireOwnedKnowledgeBase}-equivalent check each of those services already
 * had before this feature existed - nothing new was added there, and nothing needed to be: a stale
 * pointer (e.g. left over from a since-deleted knowledge base, or - defense in depth - from a
 * tenant switch that somehow didn't clear it) is caught identically to an explicit id typo.
 */
@Service
public class McpActiveKnowledgeBaseSwitchingService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final McpUserRepository mcpUserRepository;

    McpActiveKnowledgeBaseSwitchingService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository,
            McpUserRepository mcpUserRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.mcpUserRepository = mcpUserRepository;
    }

    @Transactional
    public KnowledgeBaseResponse switchKnowledgeBase(UUID mcpUserId, UUID tenantId, String name) {
        KnowledgeBase knowledgeBase =
                knowledgeBaseRepository
                        .findByNameIgnoreCaseAndTenant_Id(name, tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "Not Found",
                                                "knowledge base not found"));
        try {
            mcpUserRepository.switchActiveKnowledgeBase(mcpUserId, knowledgeBase);
        } catch (DataIntegrityViolationException e) {
            // Architect-level validation round (mcp_plan.md Grooming #29): found live, not
            // guessed - a concurrent delete_knowledge_base for this exact knowledge base, landing
            // between the lookup above and this update, violates the active_knowledge_base_id
            // foreign key and throws here. REST gets a clean translation of this exception for
            // free from ApiExceptionHandler's own global @ExceptionHandler, but MCP tool calls
            // never go through that (Spring MVC-only) layer - the MCP SDK's own tool-invocation
            // machinery would otherwise surface this exception's raw message (constraint name,
            // table, column, and the offending id value) straight to the client, confirmed live
            // before this catch existed. Treated identically to "knowledge base not found" - from
            // the caller's perspective, a knowledge base deleted between the lookup and this write
            // is indistinguishable from one that never existed, the same information-hiding
            // contract this whole feature already uses for cross-tenant names.
            throw new ApiException(HttpStatus.NOT_FOUND, "Not Found", "knowledge base not found");
        }
        DocumentStatusSummary summary =
                DocumentStatusSummary.from(
                        documentRepository.countByKnowledgeBaseIdGroupedByStatus(
                                knowledgeBase.getId()));
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getEmbeddingDim(),
                summary);
    }
}
