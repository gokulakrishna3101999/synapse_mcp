package com.synapsemcp.ingestion.extract;

import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.RateLimitKind;
import com.synapsemcp.common.RateLimited;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * rag_plan.md Stage 5a: the vision-model call shared by {@link PdfExtractor} (rendered page images)
 * and {@link ImageExtractor} (the uploaded image itself) - both send one or more PNG images plus a
 * transcription instruction to the knowledge_base's locked chat model ({@code
 * ChatModelFactory#getChatModelForKnowledgeBase}, the same snapshot resolution every other
 * knowledge_base-scoped provider call in this codebase uses, Grooming #23) and return the raw
 * response text. Deliberately thin - callers own all fallback/caching/error-handling decisions,
 * this class only knows how to make one vision call.
 */
@Component
public class VisionTranscriptionService {

    private static final String SYSTEM_PROMPT =
            "You are a document text-transcription assistant. Transcribe all readable text from the"
                    + " provided image(s) verbatim, in reading order. Preserve headings as"
                    + " Markdown-style lines starting with '#' where the visual structure makes a"
                    + " heading evident. Output only the transcribed text - no commentary, no"
                    + " preamble, no description of non-text visual content.";

    private final ChatModelFactory chatModelFactory;

    public VisionTranscriptionService(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * @param pngImages one or more images, each already encoded as PNG bytes.
     * @return the model's raw transcribed text (never null, may be blank).
     */
    @RateLimited(RateLimitKind.CHAT)
    public String transcribe(
            KnowledgeBaseModelConfig kbConfig, String instruction, List<byte[]> pngImages) {
        ChatModel chatModel = chatModelFactory.getChatModelForKnowledgeBase(kbConfig);
        ChatOptions options = chatModelFactory.optionsForKnowledgeBase(kbConfig);

        List<Media> media =
                pngImages.stream()
                        .map(
                                png ->
                                        Media.builder()
                                                .mimeType(MimeTypeUtils.IMAGE_PNG)
                                                .data(new ByteArrayResource(png))
                                                .build())
                        .toList();
        UserMessage userMessage = UserMessage.builder().text(instruction).media(media).build();
        List<Message> messages = List.of(new SystemMessage(SYSTEM_PROMPT), userMessage);

        ChatResponse response = chatModel.call(new Prompt(messages, options));
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
