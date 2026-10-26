package io.github.suppierk.shoostr.mcp;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class McpVirtualThreadTest {
  @TempDir private Path temporary;

  @Test
  void servesAnIndependentVirtualRequestWhileMcpListenersAreIdle() throws Exception {
    runFixture("requests");
  }

  @Test
  void deletesSessionsWhileOtherVirtualMcpListenersAreIdle() throws Exception {
    runFixture("delete");
  }

  @Test
  void expiresIdleVirtualMcpListenersWithinTheirAuthenticatedDeadline() throws Exception {
    runFixture("expiry");
  }

  @Test
  void shutsDownWhileVirtualMcpListenersRemainOpen() throws Exception {
    runFixture("shutdown");
  }

  @Test
  @Timeout(40)
  void continuesIdleMcpHeartbeatsAfterTheWaitTimesOut() throws Exception {
    runFixture("heartbeat");
  }

  private void runFixture(String scenario) throws Exception {
    var log = temporary.resolve(scenario + ".log");
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(
                java,
                "-Djdk.virtualThreadScheduler.parallelism=2",
                "-Djdk.virtualThreadScheduler.maxPoolSize=4",
                "-cp",
                System.getProperty("java.class.path"),
                CarrierFixture.class.getName(),
                scenario)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();

    try {
      var exited = process.waitFor("heartbeat".equals(scenario) ? 30 : 20, TimeUnit.SECONDS);
      var output = Files.readString(log);
      assertTrue(exited, "MCP carrier fixture did not exit: " + output);
      assertEquals(0, process.exitValue(), output);
      System.out.println(output.lines().findFirst().orElseThrow());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "MCP carrier fixture was not reaped");
      }
    }
  }

  public static final class CarrierFixture {
    // test-source-policy: subprocess-main; pin the virtual scheduler before its first use.
    public static void main(String[] args) throws Exception {
      assertEquals(21, Runtime.version().feature());
      System.out.println("MCP carrier fixture Java " + Runtime.version());
      var owners = ConcurrentHashMap.<Thread>newKeySet();
      var expiry = new AtomicReference<Instant>();
      var security =
          new McpHttpSecurity(
              Set.of(),
              request -> {
                if ("GET".equals(request.method())) {
                  assertTrue(Thread.currentThread().isVirtual());
                  owners.add(Thread.currentThread());
                }

                return new McpIdentity("public", McpTransportContext.EMPTY, expiry.get());
              },
              (ignored, ignored2) -> {});
      var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
      var app = new Shoostr().virtualThreads();
      app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
      app.routes()
          .get(
              "/ready",
              (ignored3, response) ->
                  response.text(Boolean.toString(Thread.currentThread().isVirtual())));

      try (var test =
          TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_1_1))) {
        assertReady(test);
        var sessions = new ArrayList<String>();
        for (int index = 0; index < 4; index++) {
          sessions.add(initialize(test));
        }
        if ("expiry".equals(args[0])) {
          expiry.set(Instant.now().plusSeconds(5));
        }

        var bodies = new ArrayList<InputStream>();
        var started = System.nanoTime();

        try {
          for (var session : sessions) {
            var response =
                test.send(
                    request ->
                        request
                            .path("/mcp")
                            .header("Accept", "text/event-stream")
                            .header("Mcp-Session-Id", session)
                            .header("MCP-Protocol-Version", "2025-11-25"),
                    HttpResponse.BodyHandlers.ofInputStream());
            var body = response.body();
            bodies.add(body);
            assertEquals(200, response.statusCode());
            assertEquals(":\n\n", new String(body.readNBytes(3), StandardCharsets.UTF_8));
          }

          await("all four virtual MCP listeners are idle after their first heartbeat")
              .pollDelay(Duration.ZERO)
              .pollInterval(Duration.ofMillis(10))
              .atMost(Duration.ofSeconds(3))
              .until(
                  () ->
                      owners.size() == 4
                          && owners.stream()
                              .allMatch(owner -> owner.getState() == Thread.State.TIMED_WAITING));
          assertTrue(
              Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0,
              "Listener setup must leave time to probe before the first 15-second heartbeat");
          if ("delete".equals(args[0])) {
            for (var session : sessions) {
              var deleted =
                  test.send(
                      request ->
                          request
                              .path("/mcp")
                              .method("DELETE")
                              .timeout(Duration.ofSeconds(2))
                              .header("Mcp-Session-Id", session)
                              .header("MCP-Protocol-Version", "2025-11-25"));
              assertEquals(200, deleted.statusCode());
            }

            awaitClosed(bodies, Duration.ofSeconds(3));
          } else if ("expiry".equals(args[0])) {
            for (var endedAt : awaitClosed(bodies, Duration.ofSeconds(7))) {
              assertTrue(
                  !endedAt.isBefore(expiry.get()),
                  "An idle MCP listener ended before its authenticated expiry");
            }
          } else if ("shutdown".equals(args[0])) {
            try (var closer = Executors.newSingleThreadExecutor()) {
              try {
                closer
                    .submit(
                        () -> {
                          app.close();
                          return null;
                        })
                    .get(3, TimeUnit.SECONDS);
              } finally {
                closer.shutdownNow();
              }
            }

            awaitClosed(bodies, Duration.ofSeconds(3));
          } else if ("heartbeat".equals(args[0])) {
            assertEquals(
                ":\n\n", new String(bodies.getFirst().readNBytes(3), StandardCharsets.UTF_8));
            assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(14))
                    >= 0,
                "An idle heartbeat must wait for the configured interval");
          } else {
            assertReady(test);
          }
        } finally {
          for (var body : bodies) {
            body.close();
          }
        }
      }
    }

    private static List<Instant> awaitClosed(List<InputStream> bodies, Duration timeout)
        throws Exception {
      try (var readers = Executors.newFixedThreadPool(4)) {
        var endings = new ArrayList<Future<Instant>>();

        try {
          for (var body : bodies) {
            endings.add(
                readers.submit(
                    () -> {
                      try {
                        body.readAllBytes();
                      } catch (IOException ignored4) {
                        // A terminated committed stream may report EOF or a transport failure.
                      }

                      return Instant.now();
                    }));
          }
          var completed = new ArrayList<Instant>();
          for (var ending : endings) {
            completed.add(ending.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
          }

          return completed;
        } finally {
          readers.shutdownNow();
        }
      }
    }

    private static void assertReady(TestServer test) throws Exception {
      var ready =
          test.send(
              request -> request.path("/ready").timeout(Duration.ofSeconds(2)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ready.statusCode());
      assertEquals("true", ready.body());
    }

    private static String initialize(TestServer test) throws Exception {
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
                "clientInfo":{"name":"carrier-fixture","version":"1.0"}}}
              """
                              .getBytes(StandardCharsets.UTF_8)));
      assertEquals(200, initialized.statusCode());
      return initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }
  }
}
