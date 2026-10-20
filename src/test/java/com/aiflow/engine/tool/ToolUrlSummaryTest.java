package com.aiflow.engine.tool;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ToolUrlSummaryTest {

    @Test
    void keepsEndpointIdentityButDropsCredentialBearingUriParts() {
        URI uri = URI.create("https://user:password@example.com:8443/api/tool?token=private-marker#fragment");

        assertThat(ToolUrlSummary.describe(uri))
                .isEqualTo("https://example.com:8443/api/tool")
                .doesNotContain("user", "password", "private-marker", "fragment", "?");
    }

    @Test
    void returnsFixedPlaceholderForNonHierarchicalUriAndNullForMissingUri() {
        assertThat(ToolUrlSummary.describe(URI.create("mailto:private-marker@example.com")))
                .isEqualTo("(unavailable URL)");
        assertThat(ToolUrlSummary.describe(null)).isNull();
    }
}
