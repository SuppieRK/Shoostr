package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Request;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpHttpSecurityTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/metadata",
        "http://example.com/metadata",
        "https://user:password@example.com/metadata",
        "https://example.com/metadata#fragment",
        "https://example.com:65536/metadata",
        "https://example.com:/metadata",
        "https:///metadata"
      })
  void rejectsInvalidDiscoveryMetadataBeforeTheAdapterCanBeCreated(String metadata) {
    Set<String> origins = Set.of();
    Function<Request, McpIdentity> identity =
        ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null);
    BiConsumer<McpIdentity, McpSchema.JSONRPCMessage> authorize = (ignored2, ignored3) -> {};
    var uri = URI.create(metadata);

    assertThrows(
        IllegalArgumentException.class,
        () -> new McpHttpSecurity(origins, identity, authorize, uri));
  }
}
