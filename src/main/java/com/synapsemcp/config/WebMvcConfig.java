package com.synapsemcp.config;

import com.synapsemcp.tenant.TenantOwnershipInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link TenantOwnershipInterceptor} against every current and future endpoint under
 * {@code /api/v1/tenants/{tenantId}/**} (audit session, 2026-07-17) - a wildcard pattern rather
 * than per-controller wiring, so a new tenant-scoped controller added later is covered
 * automatically without needing to remember this step.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantOwnershipInterceptor tenantOwnershipInterceptor;

    public WebMvcConfig(TenantOwnershipInterceptor tenantOwnershipInterceptor) {
        this.tenantOwnershipInterceptor = tenantOwnershipInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantOwnershipInterceptor)
                .addPathPatterns("/api/v1/tenants/{tenantId}/**");
    }
}
