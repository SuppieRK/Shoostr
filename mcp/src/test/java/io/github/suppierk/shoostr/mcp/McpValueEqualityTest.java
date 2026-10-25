package io.github.suppierk.shoostr.mcp;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class McpValueEqualityTest {
  @Test
  void securityComparesItsValidatedOriginsCallbacksAndMetadata() {
    var first = identity("first");
    var second = identity("second");
    EqualsVerifier.forClass(McpHttpSecurity.class)
        .withPrefabValuesForField(
            "allowedOrigins", Set.of("https://first.example"), Set.of("https://second.example"))
        .withPrefabValuesForField(
            "resourceMetadata",
            URI.create("https://first.example/metadata"),
            URI.create("https://second.example/metadata"))
        .withPrefabValues(
            Function.class, new IdentityExtractor(first), new IdentityExtractor(second))
        .withPrefabValues(
            BiConsumer.class, new IdentityAuthorizer("first"), new IdentityAuthorizer("second"))
        .withNonnullFields("allowedOrigins", "identity", "authorize")
        .verify();
  }

  @Test
  void identityComparesDetachedOwnerContextAndExpiry() {
    EqualsVerifier.forClass(McpIdentity.class)
        .withPrefabValues(
            McpTransportContext.class,
            new DetachedContext(Map.of("owner", "first")),
            new DetachedContext(Map.of("owner", "second")))
        .withNonnullFields("sessionOwner", "context")
        .verify();
  }

  @Test
  void sessionLimitsCompareAllValidatedCapacityAndTimeoutValues() {
    EqualsVerifier.forExamples(
            new McpSessionLimits(32, 8, Duration.ofMinutes(1)),
            new McpSessionLimits(64, 16, Duration.ofMinutes(2)))
        .withNonnullFields("idleTimeout")
        .verify();
  }

  static McpHttpSecurity security(String owner) {
    return new McpHttpSecurity(
        Set.of(), new IdentityExtractor(identity(owner)), new IdentityAuthorizer(owner));
  }

  private static McpIdentity identity(String owner) {
    return new McpIdentity(owner, new DetachedContext(Map.of("owner", owner)), null);
  }

  private record DetachedContext(Map<String, Object> values) implements McpTransportContext {
    private DetachedContext {
      values = Map.copyOf(values);
    }

    @Override
    public Object get(String key) {
      return values.get(key);
    }
  }

  private record IdentityExtractor(McpIdentity identity) implements Function<Request, McpIdentity> {
    private IdentityExtractor {
      Objects.requireNonNull(identity);
    }

    @Override
    public McpIdentity apply(Request request) {
      return identity;
    }
  }

  private record IdentityAuthorizer(String owner)
      implements BiConsumer<McpIdentity, McpSchema.JSONRPCMessage> {
    private IdentityAuthorizer {
      Objects.requireNonNull(owner);
    }

    @Override
    public void accept(McpIdentity identity, McpSchema.JSONRPCMessage message) {
      if (!owner.equals(identity.sessionOwner())) {
        throw new ForbiddenException();
      }
    }
  }
}
