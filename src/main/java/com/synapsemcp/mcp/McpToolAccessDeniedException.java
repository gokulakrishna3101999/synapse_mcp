package com.synapsemcp.mcp;

/**
 * mcp_plan.md Grooming #8/#13/#16: an expected, MCP-transport-specific business error - the
 * transport-agnostic counterpart to REST's {@link com.synapsemcp.common.ApiException}, which
 * carries an {@code HttpStatus} that has no meaning in an MCP tool-call context. The MCP SDK's own
 * tool-invocation machinery already catches any exception thrown from inside an {@code @McpTool}
 * method and surfaces its message as a clean tool-level error result to the client (confirmed via
 * {@code AbstractSyncMcpToolMethodCallback.createSyncErrorResult}) - no separate translation
 * adapter is needed for this one exception type, only a message safe to show a client directly.
 */
public class McpToolAccessDeniedException extends RuntimeException {

    public McpToolAccessDeniedException(String message) {
        super(message);
    }
}
