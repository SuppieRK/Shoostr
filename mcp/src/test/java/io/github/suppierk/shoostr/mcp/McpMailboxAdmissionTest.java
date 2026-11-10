package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

@Timeout(20)
class McpMailboxAdmissionTest {
  @Test
  void rejectsAKnownFullMailboxWithoutTraversingTheRejectedPayload() throws Exception {
    var acceptedTraversal = new AtomicInteger();
    var rejectedTraversal = new AtomicInteger();
    var acknowledgments = new AtomicInteger();
    var failures = new CopyOnWriteArrayList<Throwable>();
    var rejected = new AtomicReference<Throwable>();
    var response =
        sendResponseForRejectsAKnownFullMailboxWithoutTraversingTheRejectedPayload(
            acceptedTraversal, failures, acknowledgments, rejectedTraversal, rejected);

    assertEquals(200, response.statusCode());
    assertTrue(acceptedTraversal.get() > 0);
    assertInstanceOf(IOException.class, rejected.get());
    assertEquals(0, rejectedTraversal.get());
    assertEquals(64, acknowledgments.get());
    assertTrue(failures.isEmpty(), failures::toString);
    assertEquals(64, response.body().lines().filter(line -> line.startsWith("data:")).count());
  }

  private HttpResponse<String>
      sendResponseForRejectsAKnownFullMailboxWithoutTraversingTheRejectedPayload(
          AtomicInteger acceptedTraversal,
          CopyOnWriteArrayList<Throwable> failures,
          AtomicInteger acknowledgments,
          AtomicInteger rejectedTraversal,
          AtomicReference<Throwable> rejected)
          throws Exception {
    return withStream(
        stream -> {
          stream
              .sendMessage(new McpSchema.JSONRPCNotification("probe", observed(acceptedTraversal)))
              .subscribe(ignored -> {}, failures::add, acknowledgments::incrementAndGet);
          for (int index = 1; index < 64; index++) {
            stream
                .sendMessage(new McpSchema.JSONRPCNotification("probe", Map.of("value", "safe")))
                .subscribe(ignored -> {}, failures::add, acknowledgments::incrementAndGet);
          }
          stream
              .sendMessage(new McpSchema.JSONRPCNotification("probe", observed(rejectedTraversal)))
              .subscribe(ignored -> {}, rejected::set);
          stream.closeGracefully().block(Duration.ofSeconds(1));
        });
  }

  @Test
  void rejectsSubscriptionAfterClosureWithoutTraversingThePayload() throws Exception {
    var traversals = new AtomicInteger();
    var rejected = new AtomicReference<Throwable>();
    var response =
        sendResponseForRejectsSubscriptionAfterClosureWithoutTraversingThePayload(
            traversals, rejected);

    assertEquals(200, response.statusCode());
    assertInstanceOf(IOException.class, rejected.get());
    assertEquals(0, traversals.get());
    assertEquals(0, response.body().lines().filter(line -> line.startsWith("data:")).count());
  }

  private HttpResponse<String>
      sendResponseForRejectsSubscriptionAfterClosureWithoutTraversingThePayload(
          AtomicInteger traversals, AtomicReference<Throwable> rejected) throws Exception {
    return withStream(
        stream -> {
          var deferred =
              stream.sendMessage(new McpSchema.JSONRPCNotification("probe", observed(traversals)));

          assertEquals(0, traversals.get());

          stream.closeGracefully().block(Duration.ofSeconds(1));
          deferred.subscribe(ignored -> {}, rejected::set);
        });
  }

  @Test
  void rejectsClosureDuringEncodingWithoutHoldingTheMailboxLock() throws Exception {
    var traversals = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var rejected = new AtomicReference<Throwable>();
    var response = responseWhileClosingMailbox(traversals, entered, release, rejected);

    assertEquals(200, response.statusCode());
    assertTrue(traversals.get() > 0);
    assertInstanceOf(IOException.class, rejected.get());
    assertEquals(0, response.body().lines().filter(line -> line.startsWith("data:")).count());
  }

  private HttpResponse<String> responseWhileClosingMailbox(
      AtomicInteger traversals,
      CountDownLatch entered,
      CountDownLatch release,
      AtomicReference<Throwable> rejected)
      throws Exception {
    return withStream(
        stream -> {
          try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            var sending =
                submitSendingForRejectsClosureDuringEncodingWithoutHoldingTheMailboxLock(
                    senders, stream, traversals, entered, release, rejected);

            try {
              await(entered, 2);
              finish(senders.submit(() -> stream.closeGracefully().block(Duration.ofSeconds(1))));
            } finally {
              release.countDown();
            }

            finish(sending);
          }
        });
  }

  private Future<Disposable>
      submitSendingForRejectsClosureDuringEncodingWithoutHoldingTheMailboxLock(
          ExecutorService senders,
          McpStreamableServerTransport stream,
          AtomicInteger traversals,
          CountDownLatch entered,
          CountDownLatch release,
          AtomicReference<Throwable> rejected) {
    return senders.submit(
        () ->
            stream
                .sendMessage(
                    new McpSchema.JSONRPCNotification(
                        "probe", pausing(traversals, entered, release)))
                .subscribe(ignored -> {}, rejected::set));
  }

  @Test
  void rejectsCompetingAdmissionAfterEncodingWithoutExceedingTheFrameLimit() throws Exception {
    var traversals = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var rejected = new AtomicReference<Throwable>();
    var failures = new CopyOnWriteArrayList<Throwable>();
    var acknowledgments = new AtomicInteger();
    var response =
        responseWhileCompetingAdmission(
            failures, acknowledgments, traversals, entered, release, rejected);

    assertEquals(200, response.statusCode());
    assertTrue(traversals.get() > 0);
    assertInstanceOf(IOException.class, rejected.get());
    assertTrue(failures.isEmpty(), failures::toString);
    assertEquals(64, acknowledgments.get());
    assertEquals(64, response.body().lines().filter(line -> line.startsWith("data:")).count());
  }

  private HttpResponse<String> responseWhileCompetingAdmission(
      CopyOnWriteArrayList<Throwable> failures,
      AtomicInteger acknowledgments,
      AtomicInteger traversals,
      CountDownLatch entered,
      CountDownLatch release,
      AtomicReference<Throwable> rejected)
      throws Exception {
    return withStream(
        stream -> {
          for (int index = 0; index < 63; index++) {
            stream
                .sendMessage(new McpSchema.JSONRPCNotification("probe", Map.of("value", "safe")))
                .subscribe(ignored -> {}, failures::add, acknowledgments::incrementAndGet);
          }

          try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            var paused =
                submitPausedForRejectsCompetingAdmissionAfterEncodingWithoutExceedingTheFrameLimit(
                    senders, stream, traversals, entered, release, rejected);

            try {
              await(entered, 2);
              finish(
                  senders.submit(
                      () ->
                          stream
                              .sendMessage(
                                  new McpSchema.JSONRPCNotification(
                                      "probe", Map.of("value", "last")))
                              .subscribe(
                                  ignored -> {}, failures::add, acknowledgments::incrementAndGet)));
            } finally {
              release.countDown();
            }

            finish(paused);
          }

          stream.closeGracefully().block(Duration.ofSeconds(1));
        });
  }

  private Future<Disposable>
      submitPausedForRejectsCompetingAdmissionAfterEncodingWithoutExceedingTheFrameLimit(
          ExecutorService senders,
          McpStreamableServerTransport stream,
          AtomicInteger traversals,
          CountDownLatch entered,
          CountDownLatch release,
          AtomicReference<Throwable> rejected) {
    return senders.submit(
        () ->
            stream
                .sendMessage(
                    new McpSchema.JSONRPCNotification(
                        "probe", pausing(traversals, entered, release)))
                .subscribe(ignored -> {}, rejected::set));
  }

  private static Map<String, Object> pausing(
      AtomicInteger traversals, CountDownLatch entered, CountDownLatch release) {
    return observed(
        traversals,
        () -> {
          if (traversals.get() == 1) {
            entered.countDown();
            await(release, 5);
          }
        });
  }

  private static void await(CountDownLatch latch, int seconds) {
    assertDoesNotThrow(() -> assertTrue(latch.await(seconds, TimeUnit.SECONDS)));
  }

  private static void finish(Future<?> action) {
    assertDoesNotThrow(() -> action.get(2, TimeUnit.SECONDS));
  }

  private static HttpResponse<String> withStream(Consumer<McpStreamableServerTransport> exercise)
      throws Exception {
    var mcp = applicationTransport(exercise);
    var app = new Shoostr().routes(routes -> routes.path("/mcp", mcp::routes));

    try (var test = TestServer.start(app)) {
      var initialized = sendInitializedRequestForWithStream(test);

      assertEquals(200, initialized.statusCode());

      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();

      return test.send(
          request ->
              request
                  .path("/mcp")
                  .header("Accept", "text/event-stream")
                  .header("Mcp-Session-Id", session)
                  .header("MCP-Protocol-Version", "2025-11-25"),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static HttpResponse<byte[]> sendInitializedRequestForWithStream(TestServer test)
      throws IOException, InterruptedException {
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
              "clientInfo":{"name":"mailbox-test","version":"1.0"}}}
              """
                        .getBytes(StandardCharsets.UTF_8)));
  }

  private static Map<String, Object> observed(AtomicInteger traversals) {
    return observed(traversals, () -> {});
  }

  private static Map<String, Object> observed(AtomicInteger traversals, Runnable traversal) {
    return new AbstractMap<>() {
      @Override
      public Set<Entry<String, Object>> entrySet() {
        traversals.incrementAndGet();
        traversal.run();

        return Map.<String, Object>of("value", "probe").entrySet();
      }
    };
  }

  private static Mcp<McpSyncServer> applicationTransport(
      Consumer<McpStreamableServerTransport> exercise) {
    return Mcp.sync(
        new McpHttpSecurity(
            Set.of(),
            request -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (identity, message) -> {}),
        transport ->
            McpServer.sync(
                    new McpStreamableServerTransportProvider() {
                      @Override
                      public void setSessionFactory(McpStreamableServerSession.Factory factory) {
                        transport.setSessionFactory(
                            parameters -> {
                              var session = listeningSession(exercise);
                              var result = initializationResult();

                              return new McpStreamableServerSession.McpStreamableServerSessionInit(
                                  session, Mono.just(result));
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
  }

  private static McpStreamableServerSession listeningSession(
      Consumer<McpStreamableServerTransport> exercise) {
    return new McpStreamableServerSession(
        UUID.randomUUID().toString(),
        McpSchema.ClientCapabilities.builder().build(),
        McpSchema.Implementation.builder("mailbox-client", "1.0").build(),
        Duration.ofSeconds(5),
        Map.of(),
        Map.of(),
        Mono::empty,
        McpJsonDefaults.getSchemaValidator()) {
      @Override
      public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport stream) {
        var listening = super.listeningStream(stream);
        exercise.accept(stream);

        return listening;
      }
    };
  }

  private static InitializeResult initializationResult() {
    return McpSchema.InitializeResult.builder(
            "2025-11-25",
            McpSchema.ServerCapabilities.builder().build(),
            McpSchema.Implementation.builder("mailbox-server", "1.0").build())
        .build();
  }
}
