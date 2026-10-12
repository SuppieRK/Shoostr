package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpScopeDeniedExceptionTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"", "two scopes", "quote\"", "back\\slash", "newline\n", "tab\t", "é", "\u007f"})
  void rejectsInvalidOAuthScopeTokensBeforeCreatingAChallenge(String scope) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpScopeDeniedException(
                Set.of(scope),
                URI.create("https://example.com/.well-known/oauth-protected-resource")));
  }

  @Test
  void rejectsAnEmptyRequiredScopeSet() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpScopeDeniedException(
                Set.of(), URI.create("https://example.com/.well-known/oauth-protected-resource")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/metadata",
        "http://example.com/metadata",
        "https://user:password@example.com/metadata",
        "https://example.com/metadata#fragment",
        "https://example.com:65536/metadata",
        "https://example.com:/metadata"
      })
  void rejectsMetadataUrlsThatCannotBeUsedForTrustedHttpsDiscovery(String metadata) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpScopeDeniedException(Set.of("tools:read"), URI.create(metadata)));
  }

  @Test
  void snapshotsScopeTokensInsteadOfRetainingTheMutableInput() {
    var scopes = new HashSet<>(Set.of("tools:write", "tools:read"));
    var denied =
        new McpScopeDeniedException(
            scopes, URI.create("https://example.com/.well-known/oauth-protected-resource"));
    scopes.clear();
    assertEquals(
        "Bearer error=\"insufficient_scope\", scope=\"tools:read tools:write\", "
            + "resource_metadata=\"https://example.com/.well-known/oauth-protected-resource\"",
        denied.challenge());
  }
}
