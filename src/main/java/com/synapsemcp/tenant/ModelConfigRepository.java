package com.synapsemcp.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, UUID> {

    /**
     * Explicit JPQL, not Spring Data's derived-name convention - {@code findByTenantId} used to
     * work by Spring Data's own ambiguous-name fallback (no literal {@code tenantId} property
     * exists, so it split "Tenant"+"Id" and navigated {@code tenant.id}), but broke the moment
     * {@code ModelConfig} gained a real {@code getTenantId()} convenience method (added alongside
     * the package-private {@code getTenant()}/constructor fix, `plan.md` §9 2026-07-19): Spring
     * Data's property-path resolver then matched "tenantId" as a literal property instead of
     * falling back to the nested traversal, generating invalid JPQL ({@code m.tenantId} instead of
     * {@code m.tenant.id}) that failed at query-validation time with {@code UnknownPathException}.
     * Found live - every other repository method navigating a relationship in this codebase already
     * used the explicit {@code Relationship_Property} underscore syntax precisely to avoid this
     * exact ambiguity; this was the one exception.
     */
    @Query("SELECT m FROM ModelConfig m WHERE m.tenant.id = :tenantId")
    Optional<ModelConfig> findByTenantId(UUID tenantId);
}
