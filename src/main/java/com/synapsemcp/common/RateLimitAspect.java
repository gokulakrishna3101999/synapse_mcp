package com.synapsemcp.common;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 3, Grooming #5: enforces {@link RateLimited} on the domain-service layer,
 * shared by REST and MCP traffic (both already populate {@link TenantContext} before reaching any
 * of the six annotated methods - {@code ApiKeyAuthenticationFilter} for REST, {@code
 * McpToolAccessAspect} for MCP - confirmed by research, not assumed).
 *
 * <p>Resolves the provider string from the join point's own arguments using the three shapes
 * actually found across the six real call sites - no single uniform extraction works, since the
 * methods take genuinely different parameter shapes:
 *
 * <ol>
 *   <li>An argument assignable to {@link KnowledgeBaseModelConfig} is present - read {@code
 *       getChatProvider()}/{@code getEmbeddingProvider()} directly per the annotation's {@code
 *       kind} ({@code ChunkEmbeddingService.embed}, {@code LlmRerankerService.rerank}, {@code
 *       VisionTranscriptionService.transcribe}).
 *   <li>No such argument, but a {@code UUID} parameter named {@code knowledgeBaseId} is present
 *       (matched by name - {@code -parameters} is already enabled project-wide) - look up {@link
 *       KnowledgeBaseModelConfigRepository#findByKnowledgeBase_Id} ({@code
 *       RagAnsweringService.ask}/ {@code askStream}).
 *   <li>Neither present, but a {@code UUID} parameter named {@code tenantId} is present - look up
 *       {@link ModelConfigRepository#findByTenantId} for the tenant's live provider ({@code
 *       KnowledgeBaseService.createKnowledgeBase}).
 * </ol>
 */
@Aspect
@Component
public class RateLimitAspect {

    private final ProviderRateLimiter providerRateLimiter;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final ModelConfigRepository modelConfigRepository;

    RateLimitAspect(
            ProviderRateLimiter providerRateLimiter,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ModelConfigRepository modelConfigRepository) {
        this.providerRateLimiter = providerRateLimiter;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.modelConfigRepository = modelConfigRepository;
    }

    @Around("@annotation(com.synapsemcp.common.RateLimited)")
    public Object enforceRateLimit(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RateLimitKind kind = method.getAnnotation(RateLimited.class).value();
        UUID tenantId = TenantContext.get();
        String provider = resolveProvider(method, joinPoint.getArgs(), kind);

        RateLimitResult result = providerRateLimiter.tryConsume(tenantId, provider, kind);
        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for provider " + provider + " (" + kind + ")",
                    result.retryAfterSeconds());
        }

        // Deliberately not cleared here (unlike TenantContext's usual set/clear-in-finally
        // discipline): for REST, RateLimitHeaderFilter reads this only after the whole request
        // (including this call, nested well inside it) has finished, so clearing it here would
        // erase the value before that outer layer ever sees it - RateLimitHeaderFilter clears it
        // instead, once it's done reading. For MCP, McpToolAccessAspect's own finally block clears
        // it as part of its existing per-tool-call cleanup, since MCP has no equivalent outer
        // servlet filter to do so.
        RateLimitContext.set(result.remainingTokens());
        return joinPoint.proceed();
    }

    private String resolveProvider(Method method, Object[] args, RateLimitKind kind) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] instanceof KnowledgeBaseModelConfig kbConfig) {
                return kind == RateLimitKind.CHAT
                        ? kbConfig.getChatProvider()
                        : kbConfig.getEmbeddingProvider();
            }
        }
        for (int i = 0; i < parameters.length; i++) {
            if ("knowledgeBaseId".equals(parameters[i].getName()) && args[i] instanceof UUID id) {
                return providerFromKnowledgeBase(id, kind);
            }
        }
        for (int i = 0; i < parameters.length; i++) {
            if ("tenantId".equals(parameters[i].getName()) && args[i] instanceof UUID id) {
                return providerFromTenant(id, kind);
            }
        }
        throw new IllegalStateException(
                "@RateLimited method "
                        + method
                        + " has no KnowledgeBaseModelConfig, knowledgeBaseId, or tenantId"
                        + " parameter to resolve a provider from");
    }

    private String providerFromKnowledgeBase(UUID knowledgeBaseId, RateLimitKind kind) {
        KnowledgeBaseModelConfig kbConfig =
                knowledgeBaseModelConfigRepository
                        .findByKnowledgeBase_Id(knowledgeBaseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "knowledge_base "
                                                        + knowledgeBaseId
                                                        + " has no model config snapshot"));
        return kind == RateLimitKind.CHAT
                ? kbConfig.getChatProvider()
                : kbConfig.getEmbeddingProvider();
    }

    /**
     * Unlike {@link #providerFromKnowledgeBase} (a genuinely-impossible missing snapshot), a
     * missing tenant model config here is a real, expected, client-facing condition - {@code
     * KnowledgeBaseService.createKnowledgeBase} (the only caller reaching this branch) already
     * throws exactly this {@link ApiException} itself moments later for the identical reason. This
     * aspect's own lookup runs first, so it must throw the same {@code 422}, not a generic {@code
     * IllegalStateException} that would leak as an unhandled {@code 500} instead (found live,
     * `plan.md` §9 - {@code create422sWhenNoModelConfigExists} regressed until this matched).
     */
    private String providerFromTenant(UUID tenantId, RateLimitKind kind) {
        ModelConfig modelConfig =
                modelConfigRepository
                        .findByTenantId(tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "Unprocessable Entity",
                                                "model config not set for tenant"));
        return kind == RateLimitKind.CHAT
                ? modelConfig.getChatProvider()
                : modelConfig.getEmbeddingProvider();
    }
}
