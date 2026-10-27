package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.scheduler.Schedulers;

@Timeout(30)
class McpSchedulerIsolationTest {
  @TempDir private Path temporary;

  @Test
  void reclaimsIdleSessionCapacityWhileTheSharedReactorSchedulerIsSaturated() throws Exception {
    runFixture("idle");
  }

  @Test
  void completesManagedWorkAndClientRepliesWhileTheSharedReactorSchedulerIsSaturated()
      throws Exception {
    runFixture("calls");
  }

  private void runFixture(String mode) throws Exception {
    var log = temporary.resolve(mode + ".log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dreactor.schedulers.defaultBoundedElasticSize=1",
                "-Dreactor.schedulers.defaultBoundedElasticQueueSize=16",
                "-cp",
                System.getProperty("java.class.path"),
                SchedulerFixture.class.getName(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();

    try {
      var exited = process.waitFor(20, TimeUnit.SECONDS);
      var output = Files.readString(log);
      assertTrue(exited, output);
      assertEquals(0, process.exitValue(), output);
      System.out.println(output.lines().findFirst().orElseThrow());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  public static final class SchedulerFixture {
    private SchedulerFixture() {}

    // Reactor scheduler properties must be set before static initialization.
    // test-source-policy: subprocess-main
    public static void main(String[] arguments) throws Exception {
      System.out.println("Scheduler isolation fixture on Java " + Runtime.version());
      boolean idle = "idle".equals(arguments[0]);
      var mcp =
          Mcp.async(
              new McpHttpSecurity(
                  Set.of(),
                  ignored -> new McpIdentity("alice", McpTransportContext.EMPTY, null),
                  (identity, message) -> {}),
              new McpSessionLimits(1, 1, idle ? Duration.ofSeconds(1) : Duration.ofMinutes(5)),
              McpOperationLimits.defaults(),
              McpOperationLimits.defaults(),
              (transport, work) ->
                  McpServer.async(transport)
                      .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                      .tools(
                          McpServerFeatures.AsyncToolSpecification.builder()
                              .tool(
                                  McpSchema.Tool.builder("managed", Map.of("type", "object"))
                                      .build())
                              .callHandler(
                                  (exchange, request) ->
                                      work.call(
                                          () -> {
                                            new McpSyncServerExchange(exchange).ping();
                                            return McpSchema.CallToolResult.builder()
                                                .addTextContent("completed")
                                                .build();
                                          }))
                              .build())
                      .build());
      var app = new Shoostr();
      app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));

      try (var test = TestServer.start(app)) {
        var initialized = initialize(test);
        assertEquals(200, initialized.statusCode());
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker =
            Schedulers.boundedElastic()
                .schedule(
                    () -> {
                      occupied.countDown();

                      try {
                        release.await();
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    });

        try {
          assertTrue(occupied.await(3, TimeUnit.SECONDS));
          if (idle) {
            await()
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertEquals(200, initialize(test).statusCode()));
          } else {
            completeCall(test, initialized.headers().firstValue("Mcp-Session-Id").orElseThrow());
          }
        } finally {
          release.countDown();
          blocker.dispose();
        }
      }
    }

    private static void completeCall(TestServer test, String session) throws Exception {
      try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
        var response =
            test.send(
                request ->
                    request
                        .path("/mcp")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", session)
                        .header("MCP-Protocol-Version", "2025-11-25")
                        .body(
                            """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"managed","arguments":{}}}
                """
                                .getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofInputStream());

        try (var reader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          var mapper = McpJsonDefaults.getMapper();
          var ping =
              readers
                  .submit(
                      () -> {
                        String line;
                        do {
                          line = reader.readLine();
                          assertNotNull(line);
                        } while (!line.startsWith("data:"));
                        return McpSchema.deserializeJsonRpcMessage(
                            mapper, line.substring(5).strip());
                      })
                  .get(3, TimeUnit.SECONDS);
          var request = assertInstanceOf(McpSchema.JSONRPCRequest.class, ping);
          var replyBytes =
              mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(request.id(), Map.of()));
          var reply =
              test.send(
                  message ->
                      message
                          .path("/mcp")
                          .method("POST")
                          .header("Content-Type", "application/json")
                          .header("Accept", "application/json, text/event-stream")
                          .header("Mcp-Session-Id", session)
                          .header("MCP-Protocol-Version", "2025-11-25")
                          .body(replyBytes));
          assertEquals(202, reply.statusCode());
          assertTrue(
              readers
                  .submit(() -> reader.lines().anyMatch(line -> line.contains("completed")))
                  .get(3, TimeUnit.SECONDS));
        }
      }
    }

    private static HttpResponse<byte[]> initialize(TestServer test) throws Exception {
      return test.send(
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
              "clientInfo":{"name":"scheduler-isolation","version":"1.0"}}}
              """
                          .getBytes(StandardCharsets.UTF_8)));
    }
  }
}
