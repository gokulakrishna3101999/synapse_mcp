package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.ModelConfigResponse;
import com.synapsemcp.tenant.ModelConfigService;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code configure_model} tool. Flat scalar parameters (Grooming #19,
 * superseding #7) build the existing {@link ConfigureModelRequest} internally before delegating to
 * {@link ModelConfigService#configureModel}, the same fields REST's {@code PUT
 * /api/v1/tenants/{tenantId}/model-config} accepts.
 *
 * <p>User-observed gap (session 2026-07-20): an AI client is never *forced* to ask a human for real
 * provider/model/credential values before calling this tool - the MCP protocol only publishes a
 * JSON schema of accepted arguments, it has no built-in "must interactively prompt" concept, so a
 * client can (and sometimes does) fabricate plausible-looking values instead of asking, especially
 * since {@code ModelConfigService} deliberately does no eager credential validation (Grooming #4) -
 * a fabricated key silently succeeds here and only surfaces as a failure much later, at
 * knowledge_base creation or the first {@code ask} call. Closed via genuine MCP elicitation (the
 * protocol's own server-initiated "ask the connected client to prompt its user" mechanism,
 * confirmed present in {@code spring-ai-mcp-annotations:2.0.0} by decompiling {@code
 * McpSyncRequestContext}/{@code SyncMcpToolMethodCallback}, not assumed) - user-confirmed design:
 * elicit only whichever of the six fields the caller left blank (a client that already supplied a
 * real value is never second-guessed or re-prompted), and fall back to today's unchanged behavior
 * (use whatever was passed, however blank) whenever the connected client doesn't support
 * elicitation at all - {@link McpSyncRequestContext#elicitEnabled()} exists precisely to let server
 * code detect this and degrade gracefully, since elicitation support is genuinely inconsistent
 * across real MCP clients today (confirmed live this session: the official MCP Java SDK's own test
 * client declares no elicitation capability).
 */
@Component
public class ConfigureModelMcpTool {

    /**
     * Mirrors {@link ModelConfigService}'s own identically-named constant - duplicated rather than
     * exposed as shared API, since this tool only needs it to decide *whether to bother eliciting*
     * an API key at all; the actual enforcement (rejecting a still-blank key for these providers)
     * remains {@code ModelConfigService}'s job alone, unchanged.
     */
    private static final Set<String> PROVIDERS_REQUIRING_CREDENTIALS =
            Set.of("openai", "anthropic", "google-genai");

    private final ModelConfigService modelConfigService;

    public ConfigureModelMcpTool(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @McpTool(
            name = "configure_model",
            description =
                    "Sets this tenant's chat/embedding providers, models, and provider "
                            + "credentials. Mandatory prerequisite before create_knowledge_base. "
                            + "Any argument left blank is interactively requested from the human "
                            + "user when the connected client supports it; otherwise it must be "
                            + "supplied directly.")
    public ModelConfigResponse configureModel(
            McpSyncRequestContext context,
            @McpToolParam(
                            description =
                                    "Chat provider: openai, anthropic, ollama, or google-genai",
                            required = false)
                    String chatProvider,
            @McpToolParam(description = "Chat model name", required = false) String chatModel,
            @McpToolParam(
                            description = "Embedding provider: openai, ollama, or google-genai",
                            required = false)
                    String embeddingProvider,
            @McpToolParam(description = "Embedding model name", required = false)
                    String embeddingModel,
            @McpToolParam(
                            description = "API key for the chat provider, if it requires one",
                            required = false)
                    String chatApiKey,
            @McpToolParam(
                            description = "API key for the embedding provider, if it requires one",
                            required = false)
                    String embeddingApiKey) {
        chatProvider =
                elicitIfBlank(
                        context,
                        chatProvider,
                        "Which chat provider would you like to use? (openai, anthropic, ollama,"
                                + " or google-genai)");
        chatModel =
                elicitIfBlank(
                        context,
                        chatModel,
                        "Which chat model would you like to use for " + chatProvider + "?");
        embeddingProvider =
                elicitIfBlank(
                        context,
                        embeddingProvider,
                        "Which embedding provider would you like to use? (openai, ollama, or"
                                + " google-genai)");
        embeddingModel =
                elicitIfBlank(
                        context,
                        embeddingModel,
                        "Which embedding model would you like to use for "
                                + embeddingProvider
                                + "?");
        if (chatProvider != null && PROVIDERS_REQUIRING_CREDENTIALS.contains(chatProvider)) {
            chatApiKey =
                    elicitIfBlank(
                            context,
                            chatApiKey,
                            "Please enter your API key for the "
                                    + chatProvider
                                    + " chat provider.");
        }
        if (embeddingProvider != null
                && PROVIDERS_REQUIRING_CREDENTIALS.contains(embeddingProvider)) {
            embeddingApiKey =
                    elicitIfBlank(
                            context,
                            embeddingApiKey,
                            "Please enter your API key for the "
                                    + embeddingProvider
                                    + " embedding provider.");
        }

        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        chatProvider,
                        chatModel,
                        embeddingProvider,
                        embeddingModel,
                        chatApiKey,
                        embeddingApiKey);
        return modelConfigService.configureModel(TenantContext.get(), request);
    }

    /**
     * Requests a single missing value from the human user via MCP elicitation, only when {@code
     * currentValue} is genuinely blank and the connected client actually supports it - a client
     * that already supplied a real value is never re-prompted, and a client with no elicitation
     * support gets today's unchanged behavior (the blank value passes straight through to {@link
     * ModelConfigService}'s own existing validation, producing the identical client-safe error it
     * always did for a missing required field). A {@code DECLINE}/{@code CANCEL} response is
     * treated the same as "still blank" for the same reason - this method never fabricates a value
     * the user didn't actually provide.
     */
    private String elicitIfBlank(
            McpSyncRequestContext context, String currentValue, String prompt) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue;
        }
        if (!context.elicitEnabled()) {
            return currentValue;
        }
        StructuredElicitResult<ElicitedValue> result =
                context.elicit(spec -> spec.message(prompt), ElicitedValue.class);
        if (result.action() == McpSchema.ElicitResult.Action.ACCEPT
                && result.structuredContent() != null
                && result.structuredContent().value() != null
                && !result.structuredContent().value().isBlank()) {
            return result.structuredContent().value();
        }
        return currentValue;
    }

    /**
     * A single-field structured elicitation form, reused across every prompt above - each call only
     * differs by its {@code message}, so one minimal shape covers all six possible fields rather
     * than a dedicated record per field.
     */
    public record ElicitedValue(String value) {}
}
