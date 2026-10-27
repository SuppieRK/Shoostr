package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Sinks;

@Timeout(20)
class McpOperationLimitsTest {
  @Test
  void keepsAdmissionBudgetsIndependentBetweenCapabilitiesInOneApplication() throws Exception {
    var release = new CountDownLatch(1);
    var first =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport -> heldServer(transport, release, new AtomicInteger()));
    var secondInvoked = new AtomicInteger();
    var second =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport -> heldServer(transport, new CountDownLatch(0), secondInvoked));
    var app = new Shoostr();
    app.extensions(first)
        .extensions(second)
        .routes(routes -> routes.path("/first", first::routes).path("/second", second::routes));

    try (var test = TestServer.start(app)) {
      var firstSession = initialize(test, "alice", "/first");
      var secondSession = initialize(test, "alice", "/second");

      try (var body = call(test, "alice", firstSession, 2, "/first").body()) {
        try {
          var response = call(test, "alice", secondSession, 2, "/second");

          try (var independent = response.body()) {
            assertEquals(200, response.statusCode());
            independent.readAllBytes();
            assertEquals(1, secondInvoked.get());
          }
        } finally {
          release.countDown();
        }

        body.readAllBytes();
      }
    }
  }

  @Test
  void rejectsADuplicateActiveRequestIdWithoutInvokingTheCallbackAgain() throws Exception {
    var release = new CountDownLatch(1);
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 4, 4, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport -> heldServer(transport, release, invoked));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test, "alice");

      try (var first = call(test, session, 2).body()) {
        try {
          await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, invoked.get()));
          var duplicate = call(test, session, 2);

          try (var body = duplicate.body()) {
            assertEquals(400, duplicate.statusCode());
            body.readAllBytes();
          }

          assertEquals(1, invoked.get());
        } finally {
          release.countDown();
        }

        first.readAllBytes();
      }
    }
  }

  @Test
  void rejectsAllOwnersWhenCapabilityCapacityIsFullAndRecoversAfterCompletion() throws Exception {
    var release = new CountDownLatch(1);
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(1, 4, 4, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport -> heldServer(transport, release, invoked));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var alice = initialize(test, "alice");
      var bob = initialize(test, "bob");

      try (var first = call(test, "alice", alice, 2).body()) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, invoked.get()));
        var rejected = call(test, "bob", bob, 2);

        try (var body = rejected.body()) {
          assertEquals(503, rejected.statusCode());
          body.readAllBytes();
        }

        assertEquals(1, invoked.get());
        release.countDown();
        first.readAllBytes();
      } finally {
        release.countDown();
      }

      var next = call(test, "bob", bob, 3);

      try (var body = next.body()) {
        assertEquals(200, next.statusCode());
        body.readAllBytes();
        assertEquals(2, invoked.get());
      }
    }
  }

  @Test
  void boundsOneOwnerAcrossSessionsWithoutRejectingAnotherOwner() throws Exception {
    var release = new CountDownLatch(1);
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(4, 1, 4, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport -> heldServer(transport, release, invoked));
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var aliceFirst = initialize(test, "alice");
      var aliceSecond = initialize(test, "alice");
      var bob = initialize(test, "bob");
      var responses = new ArrayList<HttpResponse<InputStream>>();

      try {
        responses.add(call(test, "alice", aliceFirst, 2));
        assertEquals(200, responses.getLast().statusCode());
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, invoked.get()));
        responses.add(call(test, "alice", aliceSecond, 2));
        assertEquals(429, responses.getLast().statusCode());
        assertEquals(1, invoked.get());
        responses.add(call(test, "bob", bob, 2));
        assertEquals(200, responses.getLast().statusCode());
      } finally {
        release.countDown();
        for (var response : responses) {
          response.body().close();
        }
      }
    }
  }

  @Test
  void appliesConfiguredSessionCapacityAndReleasesItAfterCompletion() throws Exception {
    var release = new CountDownLatch(1);
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.sync(
            security(),
            McpSessionLimits.defaults(),
            new McpOperationLimits(10, 8, 1, Duration.ofMinutes(5)),
            McpOperationLimits.defaults(),
            transport ->
                McpServer.sync(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("hold", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) -> {
                                  invoked.incrementAndGet();

                                  try {
                                    if (!release.await(5, TimeUnit.SECONDS)) {
                                      throw new IllegalStateException("Fixture was not released");
                                    }
                                  } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(failure);
                                  }

                                  return McpSchema.CallToolResult.builder()
                                      .addTextContent("released")
                                      .build();
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var session = initialize(test, "alice");

      try (var first = call(test, session, 2).body()) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, invoked.get()));
        var rejected = call(test, session, 3);

        try (var body = rejected.body()) {
          assertEquals(429, rejected.statusCode());
          body.readAllBytes();
        }

        assertEquals(1, invoked.get());
        release.countDown();
        first.readAllBytes();
      } finally {
        release.countDown();
      }

      try (var next = call(test, session, 4).body()) {
        next.readAllBytes();
        assertEquals(2, invoked.get());
      }
    }
  }

  @Test
  void rejectsExcessOperationsInAnExistingSessionWithTheDefaultPolicy() throws Exception {
    var release = Sinks.<Void>empty();
    var invoked = new AtomicInteger();
    var mcp =
        Mcp.async(
            new McpHttpSecurity(
                Set.of(),
                ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                (ignored2, ignored3) -> {}),
            transport ->
                McpServer.async(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.AsyncToolSpecification.builder()
                            .tool(McpSchema.Tool.builder("hold", Map.of("type", "object")).build())
                            .callHandler(
                                (exchange, request) -> {
                                  invoked.incrementAndGet();
                                  return release
                                      .asMono()
                                      .thenReturn(
                                          McpSchema.CallToolResult.builder()
                                              .addTextContent("released")
                                              .build());
                                })
                            .build())
                    .build());
    var app = new Shoostr();
    app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-11-25","capabilities":{},
              "clientInfo":{"name":"operation-limits","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var responses = new ArrayList<HttpResponse<InputStream>>();

      try {
        for (int index = 0; index < 16; index++) {
          var response = call(test, session, index + 2);
          responses.add(response);
          assertEquals(200, response.statusCode());
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(16, invoked.get()));
        var rejected = call(test, session, 18);
        responses.add(rejected);
        assertEquals(429, rejected.statusCode());
        assertEquals(16, invoked.get());
      } finally {
        release.tryEmitEmpty();
        for (var response : responses) {
          response.body().close();
        }
      }
    }
  }

  private static HttpResponse<InputStream> call(TestServer test, String session, int id)
      throws Exception {
    return call(test, "alice", session, id);
  }

  private static HttpResponse<InputStream> call(
      TestServer test, String owner, String session, int id) throws Exception {
    return call(test, owner, session, id, "/mcp");
  }

  private static HttpResponse<InputStream> call(
      TestServer test, String owner, String session, int id, String path) throws Exception {
    return test.send(
        request ->
            request
                .path(path)
                .method("POST")
                .header("X-Owner", owner)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", "2025-11-25")
                .body(
                    ("{\"jsonrpc\":\"2.0\",\"id\":"
                            + id
                            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"hold\",\"arguments\":{}}}")
                        .getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private static McpSyncServer heldServer(
      McpStreamableServerTransportProvider transport,
      CountDownLatch release,
      AtomicInteger invoked) {
    return McpServer.sync(transport)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(
            McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("hold", Map.of("type", "object")).build())
                .callHandler(
                    (exchange, request) -> {
                      invoked.incrementAndGet();

                      try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                          throw new IllegalStateException("Fixture was not released");
                        }
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                      }

                      return McpSchema.CallToolResult.builder().addTextContent("released").build();
                    })
                .build())
        .build();
  }

  private static McpHttpSecurity security() {
    return new McpHttpSecurity(
        Set.of(),
        request ->
            new McpIdentity(
                request.header("X-Owner").orElse("alice"), McpTransportContext.EMPTY, null),
        (identity, message) -> {});
  }

  private static String initialize(TestServer test, String owner) throws Exception {
    return initialize(test, owner, "/mcp");
  }

  private static String initialize(TestServer test, String owner, String path) throws Exception {
    var response =
        test.send(
            request ->
                request
                    .path(path)
                    .method("POST")
                    .header("X-Owner", owner)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .body(
                        """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-11-25","capabilities":{},
            "clientInfo":{"name":"operation-limits","version":"1.0"}}}
            """
                            .getBytes(StandardCharsets.UTF_8)));
    assertEquals(200, response.statusCode());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
}
