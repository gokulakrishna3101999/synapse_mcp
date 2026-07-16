package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderCredentialsCodecTest {

    @Test
    void roundTripsBothCredentialsThroughEncodeAndDecode() {
        ProviderCredentials original = new ProviderCredentials("sk-chat-key", "sk-embedding-key");

        String encoded = ProviderCredentialsCodec.encode(original);
        ProviderCredentials decoded = ProviderCredentialsCodec.decode(encoded);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encodedFormIsNotPlainTextOfTheApiKeys() {
        String encoded =
                ProviderCredentialsCodec.encode(new ProviderCredentials("sk-chat-key", null));

        assertThat(encoded).doesNotContain("sk-chat-key");
    }

    @Test
    void roundTripsNullApiKeys() {
        ProviderCredentials original = new ProviderCredentials(null, null);

        ProviderCredentials decoded =
                ProviderCredentialsCodec.decode(ProviderCredentialsCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void decodingNullReturnsEmptyCredentialsInsteadOfThrowing() {
        assertThat(ProviderCredentialsCodec.decode(null))
                .isEqualTo(new ProviderCredentials(null, null));
    }

    @Test
    void decodingBlankReturnsEmptyCredentialsInsteadOfThrowing() {
        assertThat(ProviderCredentialsCodec.decode("   "))
                .isEqualTo(new ProviderCredentials(null, null));
    }
}
