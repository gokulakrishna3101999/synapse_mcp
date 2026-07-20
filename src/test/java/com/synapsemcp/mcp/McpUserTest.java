package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.tenant.Tenant;
import org.junit.jupiter.api.Test;

class McpUserTest {

    @Test
    void startsUnlinkedAndCanBeLinkedOnce() {
        McpUser user = new McpUser("alice", "hashed-password");

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(user.getTenantId()).isNull();

        Tenant tenant = new Tenant("Alice's Tenant");
        setId(tenant);
        user.linkTenant(tenant);

        assertThat(user.getTenantId()).isEqualTo(tenant.getId());
    }

    private static void setId(Tenant tenant) {
        // Tenant's id is only assigned by Hibernate on persist - unit tests that never hit a real
        // database still need a non-null id to exercise McpUser.getTenantId(), so this reaches in
        // via reflection rather than pulling in a full persistence context for one field.
        try {
            var field = Tenant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tenant, java.util.UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
