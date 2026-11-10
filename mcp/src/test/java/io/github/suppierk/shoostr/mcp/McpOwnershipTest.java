package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.Closeable;
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
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {});

    try (var attempt =
        new ConstructionAttempt(
            () -> Mcp.sync(security, ignoredMcpStreamableServerTransportProvider -> null))) {
      assertThrows(NullPointerException.class, attempt::construct);
    }
  }

  @Test
  void rejectsADuplicateNativeTransportBindingDuringConstruction() {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {});

    try (var attempt =
        openAttemptForRejectsADuplicateNativeTransportBindingDuringConstruction(security)) {
      assertThrows(IllegalStateException.class, attempt::construct);
    }
  }

  private ConstructionAttempt
      openAttemptForRejectsADuplicateNativeTransportBindingDuringConstruction(
          McpHttpSecurity security) {
    return new ConstructionAttempt(
        () ->
            Mcp.sync(
                security,
                transport -> {
                  McpServer.sync(transport).build();

                  return McpServer.sync(transport).build();
                }));
  }

  @Test
  void rejectsAnUnusedTransportWithoutClosingTheForeignServersOwner() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {});
    var mcp =
        Mcp.sync(
            security, transport -> McpServer.sync(transport).serverInfo("owner", "1.0").build());
    var app = new Shoostr();
    app.routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app);
        var client =
            openClientForRejectsAnUnusedTransportWithoutClosingTheForeignServersOwner(test)) {
      try (var attempt =
          new ConstructionAttempt(
              () ->
                  Mcp.sync(
                      security, ignoredMcpStreamableServerTransportProvider -> mcp.server()))) {
        assertThrows(IllegalArgumentException.class, attempt::construct);
      }

      assertEquals("owner", client.initialize().serverInfo().name());
      assertTrue(client.closeGracefully());
    }
  }

  private McpSyncClient openClientForRejectsAnUnusedTransportWithoutClosingTheForeignServersOwner(
      TestServer test) {
    return clientPropertiesForRejectsAnUnusedTransportWithoutClosingTheForeignServersOwner(test)
        .build();
  }

  private SyncSpec clientPropertiesForRejectsAnUnusedTransportWithoutClosingTheForeignServersOwner(
      TestServer test) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5));
  }

  @Test
  void rejectsInstallingAnAdapterThatWasAlreadyClosed() throws Exception {
    var mcp = createMcpForRejectsInstallingAnAdapterThatWasAlreadyClosed();
    mcp.close();

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        assertThrows(IllegalStateException.class, () -> app.routes().path("/mcp", mcp::routes));
      }
    }
  }

  private Mcp<McpSyncServer> createMcpForRejectsInstallingAnAdapterThatWasAlreadyClosed() {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            ignoredRequest -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignoredMcpIdentity, ignoredJSONRPCMessage) -> {}),
        transport -> McpServer.sync(transport).serverInfo("ownership", "1.0").build());
  }

  private static final class ConstructionAttempt implements AutoCloseable {
    private final Supplier<Mcp<?>> factory;
    private Optional<Mcp<?>> adapter;

    private ConstructionAttempt(Supplier<Mcp<?>> factory) {
      this.factory = Objects.requireNonNull(factory);
      this.adapter = Optional.empty();
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
