package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import java.util.Optional;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RateLimitAspectTest {

    private final ProviderRateLimiter providerRateLimiter = mock(ProviderRateLimiter.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final RateLimitAspect aspect =
            new RateLimitAspect(
                    providerRateLimiter, knowledgeBaseModelConfigRepository, modelConfigRepository);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RateLimitContext.clear();
    }

    @RateLimited(RateLimitKind.CHAT)
    void kbConfigShapeStub(KnowledgeBaseModelConfig kbConfig) {}

    @RateLimited(RateLimitKind.CHAT)
    void knowledgeBaseIdShapeStub(UUID knowledgeBaseId) {}

    @RateLimited(RateLimitKind.EMBEDDING)
    void tenantIdShapeStub(UUID tenantId) {}

    @Test
    void throwsWhenTheLimiterDenies() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        KnowledgeBaseModelConfig kbConfig =
                KnowledgeBaseModelConfig.create(
                        null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");
        when(providerRateLimiter.tryConsume(eq(tenantId), eq("openai"), eq(RateLimitKind.CHAT)))
                .thenReturn(new RateLimitResult(false, 0, 30));
        ProceedingJoinPoint joinPoint = joinPointFor("kbConfigShapeStub", kbConfig);

        assertThatThrownBy(() -> aspect.enforceRateLimit(joinPoint))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void resolvesProviderFromAKnowledgeBaseModelConfigArgument() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        KnowledgeBaseModelConfig kbConfig =
                KnowledgeBaseModelConfig.create(
                        null, "anthropic", "claude", "openai", "text-embedding-3-small", "creds");
        when(providerRateLimiter.tryConsume(eq(tenantId), eq("anthropic"), eq(RateLimitKind.CHAT)))
                .thenReturn(new RateLimitResult(true, 59, 0));
        ProceedingJoinPoint joinPoint = joinPointFor("kbConfigShapeStub", kbConfig);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.enforceRateLimit(joinPoint);

        assertThat(result).isEqualTo("ok");
        assertThat(RateLimitContext.get()).isEqualTo(59L);
    }

    @Test
    void resolvesProviderByLookingUpTheKnowledgeBaseWhenOnlyAnIdIsGiven() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        TenantContext.set(tenantId);
        KnowledgeBaseModelConfig kbConfig =
                KnowledgeBaseModelConfig.create(
                        null, "ollama", "llama3", "ollama", "nomic-embed-text", "creds");
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Id(knowledgeBaseId))
                .thenReturn(Optional.of(kbConfig));
        when(providerRateLimiter.tryConsume(eq(tenantId), eq("ollama"), eq(RateLimitKind.CHAT)))
                .thenReturn(new RateLimitResult(true, 10, 0));
        ProceedingJoinPoint joinPoint = joinPointFor("knowledgeBaseIdShapeStub", knowledgeBaseId);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.enforceRateLimit(joinPoint);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void resolvesProviderByLookingUpTheTenantsLiveModelConfigWhenOnlyATenantIdIsGiven()
            throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ModelConfig modelConfig = mock(ModelConfig.class);
        when(modelConfig.getEmbeddingProvider()).thenReturn("google-genai");
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(modelConfig));
        when(providerRateLimiter.tryConsume(
                        eq(tenantId), eq("google-genai"), eq(RateLimitKind.EMBEDDING)))
                .thenReturn(new RateLimitResult(true, 5, 0));
        ProceedingJoinPoint joinPoint = joinPointFor("tenantIdShapeStub", tenantId);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.enforceRateLimit(joinPoint);

        assertThat(result).isEqualTo("ok");
    }

    /**
     * Regression test (found live, `plan.md` §9): a missing tenant model config is a real, expected
     * condition for {@code createKnowledgeBase} - {@code KnowledgeBaseService} itself throws
     * exactly this {@code 422} moments later for the same reason. This aspect's own provider lookup
     * runs first, so it must raise the identical error, not a generic {@code IllegalStateException}
     * that would leak as an unhandled {@code 500} instead.
     */
    @Test
    void throwsA422ApiExceptionWhenTheTenantHasNoModelConfigYet() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        ProceedingJoinPoint joinPoint = joinPointFor("tenantIdShapeStub", tenantId);

        assertThatThrownBy(() -> aspect.enforceRateLimit(joinPoint))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(
                                                org.springframework.http.HttpStatus
                                                        .UNPROCESSABLE_ENTITY));
    }

    private ProceedingJoinPoint joinPointFor(String methodName, Object arg)
            throws NoSuchMethodException {
        var method =
                RateLimitAspectTest.class.getDeclaredMethod(
                        methodName,
                        arg instanceof KnowledgeBaseModelConfig
                                ? KnowledgeBaseModelConfig.class
                                : UUID.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[] {arg});
        return joinPoint;
    }
}
