package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.ModelConfigResponse;
import com.synapsemcp.tenant.ModelConfigService;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.context.McpRequestContextTypes;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;

class ConfigureModelMcpToolTest {

    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final ConfigureModelMcpTool tool = new ConfigureModelMcpTool(modelConfigService);
    private final McpSyncRequestContext context = mock(McpSyncRequestContext.class);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private ModelConfigResponse stubResponse(UUID tenantId) {
        ModelConfigResponse response =
                new ModelConfigResponse(
                        tenantId,
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        Instant.now());
        when(modelConfigService.configureModel(eq(tenantId), any())).thenReturn(response);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void stubElicitation(String value) {
        when(context.elicitEnabled()).thenReturn(true);
        StructuredElicitResult<ConfigureModelMcpTool.ElicitedValue> result =
                new StructuredElicitResult<>(
                        McpSchema.ElicitResult.Action.ACCEPT,
                        new ConfigureModelMcpTool.ElicitedValue(value),
                        Map.of());
        when(context.elicit(
                        org.mockito.ArgumentMatchers
                                .<Consumer<McpRequestContextTypes.ElicitationSpec>>any(),
                        eq(ConfigureModelMcpTool.ElicitedValue.class)))
                .thenReturn(result);
    }

    @Test
    void buildsAConfigureModelRequestFromFlatParamsAndDelegatesWhenEveryFieldIsSupplied() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ModelConfigResponse expected = stubResponse(tenantId);

        ModelConfigResponse response =
                tool.configureModel(
                        context,
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "chat-key",
                        "embed-key");

        assertThat(response).isEqualTo(expected);
        ArgumentCaptor<ConfigureModelRequest> captor =
                ArgumentCaptor.forClass(ConfigureModelRequest.class);
        verify(modelConfigService).configureModel(eq(tenantId), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(
                        new ConfigureModelRequest(
                                "openai",
                                "gpt-4o",
                                "openai",
                                "text-embedding-3-small",
                                "chat-key",
                                "embed-key"));
        verify(context, never())
                .elicit(
                        org.mockito.ArgumentMatchers
                                .<Consumer<McpRequestContextTypes.ElicitationSpec>>any(),
                        eq(ConfigureModelMcpTool.ElicitedValue.class));
    }

    @Test
    void elicitsOnlyTheFieldsLeftBlankWhenTheClientSupportsIt() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        stubResponse(tenantId);
        stubElicitation("elicited-value");

        tool.configureModel(context, "openai", null, "openai", null, "chat-key", "embed-key");

        ArgumentCaptor<ConfigureModelRequest> captor =
                ArgumentCaptor.forClass(ConfigureModelRequest.class);
        verify(modelConfigService).configureModel(eq(tenantId), captor.capture());
        // Only chatModel and embeddingModel were blank - chatProvider/embeddingProvider/both keys
        // were already supplied and must never be overwritten by an elicited value.
        assertThat(captor.getValue().chatModel()).isEqualTo("elicited-value");
        assertThat(captor.getValue().embeddingModel()).isEqualTo("elicited-value");
        assertThat(captor.getValue().chatProvider()).isEqualTo("openai");
        assertThat(captor.getValue().embeddingProvider()).isEqualTo("openai");
        assertThat(captor.getValue().chatApiKey()).isEqualTo("chat-key");
        assertThat(captor.getValue().embeddingApiKey()).isEqualTo("embed-key");
    }

    @Test
    void onlyElicitsAnApiKeyWhenTheChosenProviderActuallyRequiresOne() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        stubResponse(tenantId);
        stubElicitation("ollama-needs-no-key-but-would-get-this-if-asked");

        tool.configureModel(context, "ollama", "llama3", "ollama", "nomic-embed-text", null, null);

        ArgumentCaptor<ConfigureModelRequest> captor =
                ArgumentCaptor.forClass(ConfigureModelRequest.class);
        verify(modelConfigService).configureModel(eq(tenantId), captor.capture());
        // ollama requires no credential - both keys must stay null, never elicited.
        assertThat(captor.getValue().chatApiKey()).isNull();
        assertThat(captor.getValue().embeddingApiKey()).isNull();
    }

    @Test
    void fallsBackToTheSuppliedBlankValueWhenTheClientDoesNotSupportElicitation() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        stubResponse(tenantId);
        when(context.elicitEnabled()).thenReturn(false);

        tool.configureModel(
                context, "openai", null, "openai", "text-embedding-3-small", null, null);

        ArgumentCaptor<ConfigureModelRequest> captor =
                ArgumentCaptor.forClass(ConfigureModelRequest.class);
        verify(modelConfigService).configureModel(eq(tenantId), captor.capture());
        // Unsupported client: today's unchanged behavior - blank stays blank, passed straight
        // through to ModelConfigService's own existing validation.
        assertThat(captor.getValue().chatModel()).isNull();
        assertThat(captor.getValue().chatApiKey()).isNull();
        verify(context, never())
                .elicit(
                        org.mockito.ArgumentMatchers
                                .<Consumer<McpRequestContextTypes.ElicitationSpec>>any(),
                        eq(ConfigureModelMcpTool.ElicitedValue.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void treatsADeclinedElicitationTheSameAsStillBlankRatherThanFabricatingAValue() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        stubResponse(tenantId);
        when(context.elicitEnabled()).thenReturn(true);
        StructuredElicitResult<ConfigureModelMcpTool.ElicitedValue> declined =
                new StructuredElicitResult<>(McpSchema.ElicitResult.Action.DECLINE, null, Map.of());
        when(context.elicit(
                        org.mockito.ArgumentMatchers
                                .<Consumer<McpRequestContextTypes.ElicitationSpec>>any(),
                        eq(ConfigureModelMcpTool.ElicitedValue.class)))
                .thenReturn(declined);

        tool.configureModel(
                context, "openai", null, "openai", "text-embedding-3-small", null, null);

        ArgumentCaptor<ConfigureModelRequest> captor =
                ArgumentCaptor.forClass(ConfigureModelRequest.class);
        verify(modelConfigService).configureModel(eq(tenantId), captor.capture());
        assertThat(captor.getValue().chatModel()).isNull();
    }
}
