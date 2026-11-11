package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class McpInteractionsExampleTest {
  private static final String INITIALIZE = initializationRequest();

  private static String initializationRequest() {
    return
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":"2025-11-25","capabilities":{},
          "clientInfo":{"name":"interactions-lifecycle","version":"1.0"}}}
        """;
  }

  private static McpClient.SyncSpec client(TestServer test) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5));
  }

  private static HttpResponse<byte[]> message(TestServer test, String session, String body)
      throws Exception {
    return test.send(
        request -> {
          request
              .method("POST")
              .path("/mcp")
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("MCP-Protocol-Version", "2025-11-25")
              .body(body.getBytes(StandardCharsets.UTF_8));
          if (!session.isEmpty()) {
            request.header("Mcp-Session-Id", session);
          }
        });
  }

  @Test
  void asksForAnAudienceBeforeRequestingClientGeneratedSummary() throws Exception {
    var stages = new CopyOnWriteArrayList<String>();
    var form = new AtomicReference<McpSchema.ElicitFormRequest>();
    var sampling = new AtomicReference<McpSchema.CreateMessageRequest>();

    try (var test = TestServer.start(McpInteractionsExample.application());
        var client =
            openClientForAsksForAnAudienceBeforeRequestingClientGeneratedSummary(
                test, stages, form, sampling)) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("prepare-summary").arguments(Map.of()).build());

        assertEquals(false, result.isError());
        assertEquals(
            "Client-generated summary: The service is ready for developers.",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
        assertEquals(List.of("elicitation", "sampling"), stages);
        assertNotNull(form.get());
        assertEquals("Choose an audience for the status summary.", form.get().message());
        assertNotNull(sampling.get());
        assertEquals(
            "Summarize status 'ready' for developers.",
            assertInstanceOf(
                    McpSchema.TextContent.class, sampling.get().messages().getFirst().content())
                .text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  private McpSyncClient openClientForAsksForAnAudienceBeforeRequestingClientGeneratedSummary(
      TestServer test,
      CopyOnWriteArrayList<String> stages,
      AtomicReference<ElicitFormRequest> form,
      AtomicReference<CreateMessageRequest> sampling) {
    return clientPropertiesForAsksForAnAudienceBeforeRequestingClientGeneratedSummary(
            test, stages, form, sampling)
        .build();
  }

  private SyncSpec clientPropertiesForAsksForAnAudienceBeforeRequestingClientGeneratedSummary(
      TestServer test,
      CopyOnWriteArrayList<String> stages,
      AtomicReference<ElicitFormRequest> form,
      AtomicReference<CreateMessageRequest> sampling) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                .endpoint("/mcp")
                .build())
        .requestTimeout(Duration.ofSeconds(5))
        .initializationTimeout(Duration.ofSeconds(5))
        .capabilities(
            McpSchema.ClientCapabilities.builder().sampling().elicitation(true, false).build())
        .elicitation(
            request -> {
              stages.add("elicitation");
              form.set(request);

              return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                  .content(Map.of("audience", "developers"))
                  .build();
            })
        .sampling(
            request -> {
              stages.add("sampling");
              sampling.set(request);

              return McpSchema.CreateMessageResult.builder(
                      McpSchema.Role.ASSISTANT,
                      "The service is ready for developers.",
                      "fixture-model")
                  .build();
            });
  }

  @Test
  void sendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession() throws Exception {
    var logs = new LinkedBlockingQueue<McpSchema.LoggingMessageNotification>();
    var progress = new LinkedBlockingQueue<McpSchema.ProgressNotification>();
    var otherLogs = new LinkedBlockingQueue<McpSchema.LoggingMessageNotification>();
    var otherProgress = new LinkedBlockingQueue<McpSchema.ProgressNotification>();

    try (var test = TestServer.start(McpInteractionsExample.application());
        var initiating =
            openInitiatingForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(
                test, logs, progress);
        var other =
            client(test)
                .loggingConsumer(otherLogs::add)
                .progressConsumer(otherProgress::add)
                .build()) {
      initiating.initialize();
      other.initialize();

      try {
        initiating.setLoggingLevel(McpSchema.LoggingLevel.INFO);
        var result =
            sendResultForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(initiating);

        assertEquals(false, result.isError());

        for (var expected : List.of("Preparing a status summary", "Prepared a status summary")) {
          var log = logs.poll(3, TimeUnit.SECONDS);

          assertNotNull(log);
          assertEquals(McpSchema.LoggingLevel.INFO, log.level());
          assertEquals(expected, log.data());
        }
        for (var expected : List.of(0.0, 0.5, 1.0)) {
          var update = progress.poll(3, TimeUnit.SECONDS);

          assertNotNull(update);
          assertEquals("summary-42", update.progressToken());
          assertEquals(expected, update.progress());
          assertEquals(1.0, update.total());
        }
        other.ping();

        assertNull(otherLogs.poll(250, TimeUnit.MILLISECONDS));
        assertNull(otherProgress.poll(250, TimeUnit.MILLISECONDS));
      } finally {
        assertTrue(initiating.closeGracefully());
        assertTrue(other.closeGracefully());
      }
    }
  }

  private McpSyncClient
      openInitiatingForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(
          TestServer test,
          LinkedBlockingQueue<LoggingMessageNotification> logs,
          LinkedBlockingQueue<ProgressNotification> progress) {
    return initiatingPropertiesForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(
            test, logs, progress)
        .build();
  }

  private SyncSpec
      initiatingPropertiesForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(
          TestServer test,
          LinkedBlockingQueue<LoggingMessageNotification> logs,
          LinkedBlockingQueue<ProgressNotification> progress) {
    return client(test)
        .capabilities(
            McpSchema.ClientCapabilities.builder().sampling().elicitation(true, false).build())
        .elicitation(
            ignored ->
                McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                    .content(Map.of("audience", "developers"))
                    .build())
        .sampling(
            ignoredCreateMessageRequest ->
                McpSchema.CreateMessageResult.builder(
                        McpSchema.Role.ASSISTANT, "Ready.", "fixture-model")
                    .build())
        .loggingConsumer(logs::add)
        .progressConsumer(progress::add);
  }

  private CallToolResult sendResultForSendsLoggingAndCorrelatedProgressOnlyToTheInitiatingSession(
      McpSyncClient initiating) {
    return initiating.callTool(
        McpSchema.CallToolRequest.builder("prepare-summary")
            .arguments(Map.of())
            .progressToken("summary-42")
            .build());
  }

  @Test
  void publishesResourceChangesAndStopsUpdatesAfterUnsubscribe() throws Exception {
    var updates = new LinkedBlockingQueue<List<McpSchema.ResourceContents>>();
    var listening = new CountDownLatch(1);
    var app = McpInteractionsExample.application();
    app.beforeResponseFlush(
        (request, ignoredResponse) -> {
          if ("GET".equals(request.method()) && "/mcp".equals(request.path())) {
            listening.countDown();
          }
        });

    try (var test = TestServer.start(app);
        var client = client(test).resourcesUpdateConsumer(updates::add).build()) {
      client.initialize();

      try {
        assertTrue(listening.await(3, TimeUnit.SECONDS));

        var initial =
            client.readResource(McpSchema.ReadResourceRequest.builder("status://current").build());

        assertEquals(
            "ready",
            assertInstanceOf(McpSchema.TextResourceContents.class, initial.contents().getFirst())
                .text());

        client.subscribeResource(McpSchema.SubscribeRequest.builder("status://current").build());

        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .method("POST")
                            .path("/status")
                            .header("Content-Type", "text/plain")
                            .body("busy".getBytes(StandardCharsets.UTF_8)))
                .statusCode());

        var update = updates.poll(3, TimeUnit.SECONDS);

        assertNotNull(update);
        assertEquals(
            "busy",
            assertInstanceOf(McpSchema.TextResourceContents.class, update.getFirst()).text());

        client.unsubscribeResource(
            McpSchema.UnsubscribeRequest.builder("status://current").build());

        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .method("POST")
                            .path("/status")
                            .body("done".getBytes(StandardCharsets.UTF_8)))
                .statusCode());
        assertEquals(
            "done",
            assertInstanceOf(
                    McpSchema.TextResourceContents.class,
                    client
                        .readResource(
                            McpSchema.ReadResourceRequest.builder("status://current").build())
                        .contents()
                        .getFirst())
                .text());
        assertNull(updates.poll(250, TimeUnit.MILLISECONDS));

        client.subscribeResource(McpSchema.SubscribeRequest.builder("status://current").build());

        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .method("POST")
                            .path("/status")
                            .body("ready".getBytes(StandardCharsets.UTF_8)))
                .statusCode());

        var resumed = updates.poll(3, TimeUnit.SECONDS);

        assertNotNull(resumed);
        assertEquals(
            "ready",
            assertInstanceOf(McpSchema.TextResourceContents.class, resumed.getFirst()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"DECLINE,Summary declined", "CANCEL,Summary cancelled"})
  void preservesTheClientsDecisionWithoutSamplingOrInventingProgress(
      McpSchema.ElicitResult.Action action, String expected) throws Exception {
    var samples = new AtomicInteger();
    var progress = new LinkedBlockingQueue<McpSchema.ProgressNotification>();

    try (var test = TestServer.start(McpInteractionsExample.application());
        var client =
            openClientForPreservesTheClientsDecisionWithoutSamplingOrInventingProgress(
                test, action, samples, progress)) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("prepare-summary").arguments(Map.of()).build());

        assertEquals(false, result.isError());
        assertEquals(
            expected,
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
        assertEquals(0, samples.get());
        assertNull(progress.poll(250, TimeUnit.MILLISECONDS));
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  private McpSyncClient openClientForPreservesTheClientsDecisionWithoutSamplingOrInventingProgress(
      TestServer test,
      Action action,
      AtomicInteger samples,
      LinkedBlockingQueue<ProgressNotification> progress) {
    return clientPropertiesForPreservesTheClientsDecisionWithoutSamplingOrInventingProgress(
            test, action, samples, progress)
        .build();
  }

  private SyncSpec clientPropertiesForPreservesTheClientsDecisionWithoutSamplingOrInventingProgress(
      TestServer test,
      Action action,
      AtomicInteger samples,
      LinkedBlockingQueue<ProgressNotification> progress) {
    return client(test)
        .capabilities(
            McpSchema.ClientCapabilities.builder().sampling().elicitation(true, false).build())
        .elicitation(ignoredElicitFormRequest -> McpSchema.ElicitResult.builder(action).build())
        .sampling(
            ignoredCreateMessageRequest -> {
              samples.incrementAndGet();

              return McpSchema.CreateMessageResult.builder(
                      McpSchema.Role.ASSISTANT, "Not requested", "fixture-model")
                  .build();
            })
        .progressConsumer(progress::add);
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "sampling", "form"})
  void rejectsMissingClientCapabilitiesBeforeRequestingAnyInteraction(String available)
      throws Exception {
    var interactions = new AtomicInteger();

    try (var test = TestServer.start(McpInteractionsExample.application())) {
      var specification = client(test);
      switch (available) {
        case "sampling" ->
            specification
                .capabilities(McpSchema.ClientCapabilities.builder().sampling().build())
                .sampling(
                    ignoredCreateMessageRequest -> {
                      interactions.incrementAndGet();

                      return McpSchema.CreateMessageResult.builder(
                              McpSchema.Role.ASSISTANT, "Not requested", "fixture-model")
                          .build();
                    });
        case "form" ->
            specification
                .capabilities(
                    McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                .elicitation(
                    ignoredElicitFormRequest -> {
                      interactions.incrementAndGet();

                      return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.CANCEL)
                          .build();
                    });
        default -> specification.capabilities(McpSchema.ClientCapabilities.builder().build());
      }

      try (var client = specification.build()) {
        client.initialize();

        try {
          var result =
              client.callTool(
                  McpSchema.CallToolRequest.builder("prepare-summary").arguments(Map.of()).build());

          assertEquals(true, result.isError());
          assertEquals(
              "Client must support sampling and form elicitation",
              assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
          assertEquals(0, interactions.get());
        } finally {
          assertTrue(client.closeGracefully());
        }
      }
    }
  }

  @Test
  void rejectsAnAcceptedAudienceOutsideTheFormWithoutRequestingSampling() throws Exception {
    var samples = new AtomicInteger();

    try (var test = TestServer.start(McpInteractionsExample.application());
        var client =
            openClientForRejectsAnAcceptedAudienceOutsideTheFormWithoutRequestingSampling(
                test, samples)) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("prepare-summary").arguments(Map.of()).build());

        assertEquals(true, result.isError());
        assertEquals(
            "Client returned an invalid audience",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
        assertEquals(0, samples.get());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  private McpSyncClient
      openClientForRejectsAnAcceptedAudienceOutsideTheFormWithoutRequestingSampling(
          TestServer test, AtomicInteger samples) {
    return clientPropertiesForRejectsAnAcceptedAudienceOutsideTheFormWithoutRequestingSampling(
            test, samples)
        .build();
  }

  private SyncSpec
      clientPropertiesForRejectsAnAcceptedAudienceOutsideTheFormWithoutRequestingSampling(
          TestServer test, AtomicInteger samples) {
    return client(test)
        .capabilities(
            McpSchema.ClientCapabilities.builder().sampling().elicitation(true, false).build())
        .elicitation(
            ignoredElicitFormRequest ->
                McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                    .content(Map.of("audience", "root"))
                    .build())
        .sampling(
            ignoredCreateMessageRequest -> {
              samples.incrementAndGet();

              return McpSchema.CreateMessageResult.builder(
                      McpSchema.Role.ASSISTANT, "Not requested", "fixture-model")
                  .build();
            });
  }

  @Test
  void rejectsBlankClientGeneratedSummary() throws Exception {

    try (var test = TestServer.start(McpInteractionsExample.application());
        var client = openClientForRejectsBlankClientGeneratedSummary(test)) {
      client.initialize();

      try {
        var result =
            client.callTool(
                McpSchema.CallToolRequest.builder("prepare-summary").arguments(Map.of()).build());

        assertEquals(true, result.isError());
        assertEquals(
            "Client returned no text summary",
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }

  private McpSyncClient openClientForRejectsBlankClientGeneratedSummary(TestServer test) {
    return clientPropertiesForRejectsBlankClientGeneratedSummary(test).build();
  }

  private SyncSpec clientPropertiesForRejectsBlankClientGeneratedSummary(TestServer test) {
    return client(test)
        .capabilities(
            McpSchema.ClientCapabilities.builder().sampling().elicitation(true, false).build())
        .elicitation(
            ignoredElicitFormRequest ->
                McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                    .content(Map.of("audience", "operators"))
                    .build())
        .sampling(
            ignoredCreateMessageRequest ->
                McpSchema.CreateMessageResult.builder(
                        McpSchema.Role.ASSISTANT, " ", "fixture-model")
                    .build());
  }

  @Test
  void invalidatesAnExplicitlyDeletedSessionWithoutStoppingAnotherSession() throws Exception {

    try (var test = TestServer.start(McpInteractionsExample.application())) {
      var first = message(test, "", INITIALIZE);
      var second = message(test, "", INITIALIZE);

      assertEquals(200, first.statusCode());
      assertEquals(200, second.statusCode());

      var firstSession = first.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var secondSession = second.headers().firstValue("Mcp-Session-Id").orElseThrow();

      assertEquals(
          200,
          test.send(
                  request ->
                      request
                          .method("DELETE")
                          .path("/mcp")
                          .header("Mcp-Session-Id", firstSession)
                          .header("MCP-Protocol-Version", "2025-11-25"))
              .statusCode());

      var ping = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";

      assertEquals(404, message(test, firstSession, ping).statusCode());
      assertEquals(200, message(test, secondSession, ping).statusCode());
    }
  }

  @Test
  void endsAnAttachedStreamWhenTheApplicationClosesBeforeItsHttpClient() throws Exception {
    var app = McpInteractionsExample.application();

    try (var readers = Executors.newVirtualThreadPerTaskExecutor();
        var test = TestServer.start(app)) {
      var initialized = message(test, "", INITIALIZE);

      assertEquals(200, initialized.statusCode());

      var session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      var listening =
          sendListeningRequestForEndsAnAttachedStreamWhenTheApplicationClosesBeforeItsHttpClient(
              test, session);

      assertEquals(200, listening.statusCode());

      try (var input = listening.body()) {
        var ended = readers.submit(input::readAllBytes);

        assertThrows(TimeoutException.class, () -> ended.get(200, TimeUnit.MILLISECONDS));

        app.close();

        try {
          ended.get(3, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      }
    }
  }

  private HttpResponse<InputStream>
      sendListeningRequestForEndsAnAttachedStreamWhenTheApplicationClosesBeforeItsHttpClient(
          TestServer test, String session) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/mcp")
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Accept", "text/event-stream"),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  @Test
  void validatesHttpUpdatesBeforeChangingTheResource() throws Exception {

    try (var test = TestServer.start(McpInteractionsExample.application());
        var client = client(test).build()) {
      client.initialize();

      try {
        var accepted = "é".repeat(80);

        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .method("POST")
                            .path("/status")
                            .body(("  " + accepted + "  ").getBytes(StandardCharsets.UTF_8)))
                .statusCode());

        for (var invalid : List.of("", " ", "one\ntwo", "x".repeat(81))) {
          assertEquals(
              400,
              test.send(
                      request ->
                          request
                              .method("POST")
                              .path("/status")
                              .body(invalid.getBytes(StandardCharsets.UTF_8)))
                  .statusCode());

          var value =
              client.readResource(
                  McpSchema.ReadResourceRequest.builder("status://current").build());

          assertEquals(
              accepted,
              assertInstanceOf(McpSchema.TextResourceContents.class, value.contents().getFirst())
                  .text());
        }
      } finally {
        assertTrue(client.closeGracefully());
      }
    }
  }
}
