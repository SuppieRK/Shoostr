package io.github.suppierk.shoostr.example.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.mcp.McpOperationLimits;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(20)
class McpOperationsExampleTest {
  @ParameterizedTest
  @MethodSource("admissionPolicies")
  void rejectsExcessWorkAndRecoversAfterMatchingCancellation(
      McpOperationLimits ordinary, int status) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var app =
        McpOperationsExample.application(
            () -> {
              if (calls.getAndIncrement() == 0) {
                entered.countDown();
                release.await();
              }

              return "healthy";
            },
            ordinary,
            new McpOperationLimits(2, 2, 2, Duration.ofMinutes(5)));

    try (var test = TestServer.start(app);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var session = initialize(test);

      try (var reader = openStatus(test, session, 2)) {
        try {
          answerPing(test, session, reader);
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var rejected = open(test, session, status(3));

          try (var body = rejected.body()) {
            assertEquals(status, rejected.statusCode());
            body.readAllBytes();
          }

          assertEquals(1, calls.get());
          assertEquals(
              202,
              post(
                      test,
                      session,
                      """
              {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}
              """)
                  .statusCode());
          var remaining = readers.submit(() -> reader.lines().toList()).get(3, TimeUnit.SECONDS);
          assertFalse(remaining.stream().anyMatch(line -> line.startsWith("data:")));
        } finally {
          release.countDown();
        }
      }

      try (var recovered = openStatus(test, session, 4)) {
        answerPing(test, session, recovered);
        assertTrue(recovered.lines().anyMatch(line -> line.contains("healthy")));
      }
    }
  }

  @Test
  void keepsTimedOutWorkChargedUntilItsCallableActuallyExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var app =
        McpOperationsExample.application(
            () -> {
              if (calls.getAndIncrement() != 0) {
                return "healthy";
              }

              entered.countDown();
              boolean done = false;
              while (!done) {
                try {
                  done = release.await(10, TimeUnit.SECONDS);
                  if (!done) {
                    throw new IllegalStateException("Fixture was not released");
                  }
                } catch (InterruptedException ignored) {
                  // Keep the callback alive so the test can distinguish cancellation from actual
                  // exit.
                }
              }
              return "too late";
            },
            new McpOperationLimits(4, 2, 1, Duration.ofSeconds(2)),
            new McpOperationLimits(4, 2, 1, Duration.ofMinutes(5)));

    try (var test = TestServer.start(app);
        var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var session = initialize(test);

      try (var reader = openStatus(test, session, 2)) {
        try {
          answerPing(test, session, reader);
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var lines = readers.submit(() -> reader.lines().toList()).get(4, TimeUnit.SECONDS);
          assertTrue(
              lines.stream()
                  .anyMatch(line -> line.contains("\"id\":2") && line.contains("\"code\":-32001")));
          assertFalse(lines.stream().anyMatch(line -> line.contains("too late")));
          var rejected = open(test, session, status(3));

          try (var body = rejected.body()) {
            assertEquals(429, rejected.statusCode());
            body.readAllBytes();
          }

          assertEquals(1, calls.get());
        } finally {
          release.countDown();
        }
      }

      try (var recovered = openStatus(test, session, 4)) {
        answerPing(test, session, recovered);
        assertTrue(recovered.lines().anyMatch(line -> line.contains("healthy")));
      }
    }
  }

  @Test
  void answersClientRepliesWhileTheStatusOperationOccupiesTheOnlyOrdinarySlot() throws Exception {
    var ordinary = new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5));
    var control = new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5));
    var app = McpOperationsExample.application(() -> "healthy", ordinary, control);

    try (var test = TestServer.start(app);
        var client =
            McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(3))
                .build()) {
      client.initialize();
      var result = client.callTool(McpSchema.CallToolRequest.builder("status").build());
      assertEquals(
          "healthy",
          assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
    }
  }

  private static String initialize(TestServer test) throws Exception {
    var response =
        post(
            test,
            null,
            """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"operations-example","version":"1.0"}}}
        """);
    assertEquals(200, response.statusCode());
    var session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
    assertEquals(
        202,
        post(test, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
            .statusCode());
    return session;
  }

  private static Stream<Arguments> admissionPolicies() {
    return Stream.of(
        Arguments.of(new McpOperationLimits(4, 2, 1, Duration.ofMinutes(5)), 429),
        Arguments.of(new McpOperationLimits(1, 1, 1, Duration.ofMinutes(5)), 503));
  }

  private static BufferedReader openStatus(TestServer test, String session, int id)
      throws Exception {
    var accepted = new AtomicReference<HttpResponse<InputStream>>();
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              var response = open(test, session, status(id));
              if (response.statusCode() != 200) {
                response.body().close();
              }

              assertEquals(200, response.statusCode());
              accepted.set(response);
            });
    return new BufferedReader(new InputStreamReader(accepted.get().body(), StandardCharsets.UTF_8));
  }

  private static void answerPing(TestServer test, String session, BufferedReader reader)
      throws Exception {
    String line;
    do {
      line = reader.readLine();
      assertNotNull(line);
    } while (!line.startsWith("data:"));
    var mapper = McpJsonDefaults.getMapper();
    var ping =
        assertInstanceOf(
            McpSchema.JSONRPCRequest.class,
            McpSchema.deserializeJsonRpcMessage(mapper, line.substring(5).strip()));
    assertEquals("ping", ping.method());
    assertEquals(
        202,
        post(
                test,
                session,
                mapper.writeValueAsString(McpSchema.JSONRPCResponse.result(ping.id(), Map.of())))
            .statusCode());
  }

  private static String status(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}}}";
  }

  private static HttpResponse<InputStream> open(TestServer test, String session, String json)
      throws Exception {
    return test.send(
        request ->
            request
                .path("/mcp")
                .method("POST")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Mcp-Session-Id", session)
                .body(json.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofInputStream());
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
