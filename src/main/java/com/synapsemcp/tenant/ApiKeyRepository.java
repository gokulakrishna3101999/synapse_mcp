package com.synapsemcp.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("SELECT a.tenant.id FROM ApiKey a WHERE a.keyHash = :keyHash")
    Optional<UUID> findTenantIdByKeyHash(String keyHash);
}
