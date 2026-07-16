package com.synapsemcp.tenant;

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

/**
 * One config row per tenant (rag_plan.md Stage 0.5 / Grooming #17, #19). {@code chat_provider} and
 * {@code embedding_provider} are additionally constrained by a CHECK constraint added by {@link
 * com.synapsemcp.config.AnnIndexBootstrapRunner}, since plain JPA has no CHECK annotation.
 */
@Entity
@Table(name = "model_configs")
public class ModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @Column(name = "chat_provider", nullable = false)
    private String chatProvider;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    /** JSON {"chatApiKey": "", "embeddingApiKey": ""}, Base64-encoded (rag_plan.md Stage 2). */
    @Column(name = "provider_credentials", columnDefinition = "TEXT")
    private String providerCredentials;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelConfig() {}

    public ModelConfig(
            Tenant tenant,
            String chatProvider,
            String chatModel,
            String embeddingProvider,
            String embeddingModel,
            String providerCredentials) {
        this.tenant = tenant;
        this.chatProvider = chatProvider;
        this.chatModel = chatModel;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
        this.providerCredentials = providerCredentials;
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getChatProvider() {
        return chatProvider;
    }

    public void setChatProvider(String chatProvider) {
        this.chatProvider = chatProvider;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
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
