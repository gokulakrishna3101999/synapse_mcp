package com.synapsemcp.mcp;

import java.util.UUID;

/**
 * mcp_plan.md Stage 2 {@code delete_knowledge_base} tool result. REST's {@code DELETE
 * /api/v1/knowledgebase/{id}} returns a bare {@code 204} - an MCP tool result needs something to
 * return, so this is that equivalent.
 */
public record DeleteKnowledgeBaseResult(UUID knowledgeBaseId, boolean deleted) {}
