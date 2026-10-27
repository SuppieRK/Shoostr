package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

@Timeout(15)
class McpDispatchRollbackTest {
  @Test
  void releasesAdmissionWhenNativeResponseStreamConstructionFailsBeforeSubscription()
      throws Exception {
    var first = new AtomicBoolean(true);
    var mcp =
        Mcp.sync(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                (identity, message) -> {}),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport ->
                McpServer.sync(
                        new McpStreamableServerTransportProvider() {
                          @Override
                          public void setSessionFactory(
                              McpStreamableServerSession.Factory factory) {
                            transport.setSessionFactory(
                                parameters -> {
                                  McpRequestHandler<Object> ping =
                                      (exchange, request) -> Mono.just(Map.of());
                                  var session =
                                      new McpStreamableServerSession(
                                          UUID.randomUUID().toString(),
                                          McpSchema.ClientCapabilities.builder().build(),
                                          McpSchema.Implementation.builder("rollback", "1.0")
                                              .build(),
                                          Duration.ofSeconds(5),
                                          Map.of("ping", ping),
                                          Map.of(),
                                          Mono::empty,
                                          McpJsonDefaults.getSchemaValidator()) {
                                        @Override
                                        public McpStreamableServerSessionStream responseStream(
                                            McpStreamableServerTransport connection) {
                                          if (first.compareAndSet(true, false)) {
                                            throw new IllegalStateException("Stream setup failed");
                                          }

                                          return super.responseStream(connection);
                                        }
                                      };
                                  var result =
                                      McpSchema.InitializeResult.builder(
                                              "2025-11-25",
                                              McpSchema.ServerCapabilities.builder().build(),
                                              McpSchema.Implementation.builder("rollback", "1.0")
                                                  .build())
                                          .build();
                                  return new McpStreamableServerSession
                                      .McpStreamableServerSessionInit(session, Mono.just(result));
                                });
                          }

                          @Override
                          public Mono<Void> notifyClients(String method, Object parameters) {
                            return transport.notifyClients(method, parameters);
                          }

                          @Override
                          public Mono<Void> closeGracefully() {
                            return transport.closeGracefully();
                          }

                          @Override
                          public List<String> protocolVersions() {
                            return transport.protocolVersions();
                          }
                        })
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          post(
              test,
              null,
              """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-11-25","capabilities":{},
              "clientInfo":{"name":"rollback","version":"1.0"}}}
              """);
      assertEquals(200, initialized.statusCode());
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      assertEquals(
          500,
          post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}").statusCode());
      assertEquals(
          200,
          post(test, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}").statusCode());
    }
  }

  private static HttpResponse<byte[]> post(TestServer test, String session, String json)
      throws Exception {
    return test.send(
        request -> {
          request
              .path("/mcp")
              .method("POST")
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("MCP-Protocol-Version", "2025-11-25")
              .body(json.getBytes(StandardCharsets.UTF_8));
          if (session != null) {
            request.header("Mcp-Session-Id", session);
          }
        });
  }
}
