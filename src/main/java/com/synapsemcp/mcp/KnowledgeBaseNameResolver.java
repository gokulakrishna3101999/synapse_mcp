package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * User-requested (2026-07-22): every knowledge-base-scoped MCP tool (ask/search/ingest/evaluate/
 * update_knowledge_base/delete_knowledge_base) previously took an explicit {@code knowledgeBaseId}
 * (optional, falling back to the caller's active knowledge base). Replaced entirely with a {@code
 * knowledgeBaseName} parameter instead - a deliberate breaking change, confirmed with the user
 * rather than assumed, since any caller passing {@code knowledgeBaseId} today would need to switch
 * to passing a name instead (an unrecognized argument name is silently ignored by the MCP
 * annotation framework's parameter binding, not rejected, so this is not a compile-time-visible
 * change for callers - worth remembering when auditing this feature again later).
 *
 * <p>Name lookup is scoped to the caller's own {@link TenantContext} ({@link
 * KnowledgeBaseRepository#findByNameIgnoreCaseAndTenant_Id}, the exact same repository method
 * {@link McpActiveKnowledgeBaseSwitchingService} already uses for {@code switch_knowledge_base}) -
 * a name belonging to another tenant's knowledge base is indistinguishable from a nonexistent one,
 * matching the "not yours == doesn't exist" contract every other knowledge-base lookup in this
 * codebase already uses. Falls back to the authenticated principal's active knowledge base (see
 * {@code switch_knowledge_base}) when the name is blank, exactly like the id-based resolution this
 * replaces.
 */
@Component
class KnowledgeBaseNameResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    KnowledgeBaseNameResolver(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    UUID resolve(String explicitKnowledgeBaseName) {
        if (explicitKnowledgeBaseName != null && !explicitKnowledgeBaseName.isBlank()) {
            KnowledgeBase knowledgeBase =
                    knowledgeBaseRepository
                            .findByNameIgnoreCaseAndTenant_Id(
                                    explicitKnowledgeBaseName, TenantContext.get())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Not Found",
                                                    "knowledge base not found"));
            return knowledgeBase.getId();
        }
        UUID activeKnowledgeBaseId = currentPrincipal().getActiveKnowledgeBaseId();
        if (activeKnowledgeBaseId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "no knowledgeBaseName provided and no active knowledge base set - pass"
                            + " knowledgeBaseName explicitly or call switch_knowledge_base first");
        }
        return activeKnowledgeBaseId;
    }

    private McpUserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof McpUserPrincipal principal)) {
            throw new IllegalStateException(
                    "an @McpTool method was invoked without an authenticated McpUserPrincipal");
        }
        return principal;
    }
}
