package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * User-requested (2026-07-22): every knowledge-base-scoped MCP tool's {@code knowledgeBaseId}
 * parameter was replaced entirely with {@code knowledgeBaseName}, resolved by this class - the
 * direct successor to the now-removed {@code McpToolInputs.resolveKnowledgeBaseId} (id-based) it
 * replaces test-for-test.
 */
class KnowledgeBaseNameResolverTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseNameResolver resolver =
            new KnowledgeBaseNameResolver(knowledgeBaseRepository);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void resolvesTheExplicitNameScopedToTheCurrentTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        authenticateAs(UUID.randomUUID(), UUID.randomUUID());
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBase.getId()).thenReturn(kbId);
        when(knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id("my-kb", tenantId))
                .thenReturn(Optional.of(knowledgeBase));

        assertThat(resolver.resolve("my-kb")).isEqualTo(kbId);
    }

    @Test
    void rejectsAnExplicitNameThatDoesNotBelongToTheCurrentTenant() {
        TenantContext.set(UUID.randomUUID());
        authenticateAs(UUID.randomUUID(), UUID.randomUUID());
        when(knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id(
                        "someone-elses-kb", TenantContext.get()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("someone-elses-kb"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("knowledge base not found");
    }

    @Test
    void fallsBackToTheActiveKnowledgeBaseWhenTheNameIsOmitted() {
        UUID active = UUID.randomUUID();
        authenticateAs(UUID.randomUUID(), active);

        assertThat(resolver.resolve(null)).isEqualTo(active);
        assertThat(resolver.resolve("")).isEqualTo(active);
        assertThat(resolver.resolve("   ")).isEqualTo(active);
    }

    @Test
    void rejectsCleanlyWhenNothingIsExplicitAndNothingIsActive() {
        authenticateAs(UUID.randomUUID(), null);

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("switch_knowledge_base");
    }

    private void authenticateAs(UUID mcpUserId, UUID activeKnowledgeBaseId) {
        McpUserPrincipal principal =
                new McpUserPrincipal(
                        mcpUserId, "alice", "hash", UUID.randomUUID(), activeKnowledgeBaseId);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }
}
