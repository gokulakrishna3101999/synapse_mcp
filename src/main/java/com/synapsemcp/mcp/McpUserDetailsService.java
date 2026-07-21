package com.synapsemcp.mcp;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** mcp_plan.md Stage 1 - backs {@link McpSecurityConfig}'s HTTP Basic authentication. */
@Component
public class McpUserDetailsService implements UserDetailsService {

    private final McpUserRepository mcpUserRepository;

    McpUserDetailsService(McpUserRepository mcpUserRepository) {
        this.mcpUserRepository = mcpUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        McpUser user =
                mcpUserRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new UsernameNotFoundException(
                                                "no mcp_users account for username " + username));
        return new McpUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getTenantId(),
                user.getActiveKnowledgeBaseId());
    }
}
