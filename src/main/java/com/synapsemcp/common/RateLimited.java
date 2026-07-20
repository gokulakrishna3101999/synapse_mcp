package com.synapsemcp.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * mcp_plan.md Stage 3, Grooming #5: marks a domain-service method that makes a real, paid chat or
 * embedding provider call, enforced by {@link RateLimitAspect}. Placed on the six real call sites
 * found by research - always the public, externally-invoked entry point, never a private
 * self-invoked helper (Spring AOP proxies can't intercept private methods, and self-invocation
 * bypasses the proxy regardless of visibility - see {@code
 * KnowledgeBaseService.createKnowledgeBase} for the one place this mattered).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {

    RateLimitKind value();
}
