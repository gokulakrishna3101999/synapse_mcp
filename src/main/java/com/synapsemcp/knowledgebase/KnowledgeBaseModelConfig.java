package com.synapsemcp.knowledgebase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Permanent snapshot of the tenant's {@link com.synapsemcp.tenant.ModelConfig} taken at
 * knowledge-base creation time (rag_plan.md Stage 3, Grooming #23) - locks the KB to a specific
 * model/dimension forever; only {@code provider_credentials} is ever re-synced (Grooming #22).
 */
@Entity
@Table(name = "knowledge_base_model_configs")
public class KnowledgeBaseModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private KnowledgeBase knowledgeBase;

    @Column(name = "chat_provider", nullable = false)
    private String chatProvider;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    @Column(name = "provider_credentials", columnDefinition = "TEXT")
    private String providerCredentials;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeBaseModelConfig() {}

    public KnowledgeBaseModelConfig(
            KnowledgeBase knowledgeBase,
            String chatProvider,
            String chatModel,
            String embeddingProvider,
            String embeddingModel,
            String providerCredentials) {
        this.knowledgeBase = knowledgeBase;
        this.chatProvider = chatProvider;
        this.chatModel = chatModel;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
        this.providerCredentials = providerCredentials;
    }

    public UUID getId() {
        return id;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public String getChatProvider() {
        return chatProvider;
    }

    public String getChatModel() {
        return chatModel;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getProviderCredentials() {
        return providerCredentials;
    }

    public void setProviderCredentials(String providerCredentials) {
        this.providerCredentials = providerCredentials;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
