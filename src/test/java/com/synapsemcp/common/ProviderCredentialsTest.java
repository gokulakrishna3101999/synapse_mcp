package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderCredentialsTest {

    @Test
    void toStringRedactsBothApiKeysInsteadOfPrintingThemInPlainText() {
        ProviderCredentials credentials =
                new ProviderCredentials("sk-chat-secret", "sk-embedding-secret");

        String rendered = credentials.toString();

        assertThat(rendered).doesNotContain("sk-chat-secret", "sk-embedding-secret");
        assertThat(rendered).contains("[REDACTED]");
    }

    @Test
    void toStringRendersNullKeysAsNullNotRedacted() {
        ProviderCredentials credentials = new ProviderCredentials(null, null);

        assertThat(credentials.toString()).doesNotContain("[REDACTED]").contains("null");
    }

    @Test
    void toStringOverrideDoesNotBreakRecordEquality() {
        ProviderCredentials a = new ProviderCredentials("sk-chat", "sk-embed");
        ProviderCredentials b = new ProviderCredentials("sk-chat", "sk-embed");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
