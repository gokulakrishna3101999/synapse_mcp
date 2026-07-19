package com.synapsemcp.ingestion.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

class VisionTranscriptionServiceTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final VisionTranscriptionService service =
            new VisionTranscriptionService(chatModelFactory);
    private final KnowledgeBaseModelConfig kbConfig =
            KnowledgeBaseModelConfig.create(
                    null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");

    @Test
    void sendsImagesAndInstructionAndReturnsTheResponseText() {
        when(chatModelFactory.getChatModelForKnowledgeBase(kbConfig)).thenReturn(chatModel);
        when(chatModelFactory.optionsForKnowledgeBase(kbConfig))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("transcribed text")))));

        String result =
                service.transcribe(kbConfig, "Transcribe this.", List.of(new byte[] {1, 2, 3}));

        assertThat(result).isEqualTo("transcribed text");
    }

    @Test
    void returnsBlankTextAsIsRatherThanThrowing() {
        when(chatModelFactory.getChatModelForKnowledgeBase(kbConfig)).thenReturn(chatModel);
        when(chatModelFactory.optionsForKnowledgeBase(kbConfig))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));

        String result = service.transcribe(kbConfig, "Transcribe this.", List.of(new byte[] {1}));

        assertThat(result).isEmpty();
    }
}
