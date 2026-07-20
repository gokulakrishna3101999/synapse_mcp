package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.tenant.ApiKeyHasher;
import com.synapsemcp.tenant.ApiKeyRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class McpUserRegistrationServiceTest {

    private final McpUserRepository mcpUserRepository = mock(McpUserRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final McpUserRegistrationService service =
            new McpUserRegistrationService(
                    mcpUserRepository, apiKeyRepository, tenantRepository, passwordEncoder);

    @Test
    void registersAnUnlinkedAccountWhenNoApiKeyIsSupplied() {
        when(passwordEncoder.encode("secret")).thenReturn("hashed");
        when(mcpUserRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterMcpUserResponse response =
                service.register(new RegisterMcpUserRequest("alice", "secret", null));

        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.tenantId()).isNull();
    }

    @Test
    void linksTheTenantWhenAValidApiKeyIsSupplied() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex("real-key")))
                .thenReturn(Optional.of(tenantId));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(mcpUserRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterMcpUserResponse response =
                service.register(new RegisterMcpUserRequest("bob", "secret", "real-key"));

        assertThat(response.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void rejectsAnInvalidApiKeyWith422RatherThanRegisteringUnlinked() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex("bad-key")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.register(
                                        new RegisterMcpUserRequest("carol", "secret", "bad-key")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(422));
        verify(mcpUserRepository, org.mockito.Mockito.never()).save(any());
    }
}
