package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class McpOwnershipTest {
  @Test
  void rejectsANullNativeServerBeforeReturningAnAdapter() {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});

    try (var attempt = new ConstructionAttempt(() -> Mcp.sync(security, _ -> null))) {
      assertThrows(NullPointerException.class, attempt::construct);
    }
  }

  @Test
  void rejectsADuplicateNativeTransportBindingDuringConstruction() {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});

    try (var attempt =
        new ConstructionAttempt(
            () ->
                Mcp.sync(
                    security,
                    transport -> {
                      McpServer.sync(transport).build();
                      return McpServer.sync(transport).build();
                    }))) {
      assertThrows(IllegalStateException.class, attempt::construct);
    }
  }

  @Test
  void rejectsAnUnusedTransportWithoutClosingTheForeignServersOwner() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (_, _) -> {});
    var mcp =
        Mcp.sync(
            security, transport -> McpServer.sync(transport).serverInfo("owner", "1.0").build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build()) {
      try (var attempt = new ConstructionAttempt(() -> Mcp.sync(security, _ -> mcp.server()))) {
        assertThrows(IllegalArgumentException.class, attempt::construct);
      }

      assertEquals("owner", client.initialize().serverInfo().name());
      assertTrue(client.closeGracefully());
    }
  }

  @Test
  void rejectsInstallingAnAdapterThatWasAlreadyClosed() throws Exception {
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                _ -> new McpIdentity("public", McpTransportContext.EMPTY, null),
                (_, _) -> {}),
            transport -> McpServer.sync(transport).serverInfo("ownership", "1.0").build());
    mcp.close();

    try (var app = new Shoostr()) {
      assertThrows(IllegalStateException.class, () -> app.extensions(mcp));
    }
  }

  private static final class ConstructionAttempt implements AutoCloseable {
    private final Supplier<Mcp<?>> factory;
    private Optional<Mcp<?>> adapter;

    private ConstructionAttempt(Supplier<Mcp<?>> factory) {
      this.factory = Objects.requireNonNull(factory);
      adapter = Optional.empty();
    }

    private void construct() {
      adapter = Optional.ofNullable(factory.get());
    }

    @Override
    public void close() {
      adapter.ifPresent(Mcp::close);
    }
  }
}
