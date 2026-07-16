package com.synapsemcp.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, UUID> {

    Optional<ModelConfig> findByTenantId(UUID tenantId);
}
