package io.github.suppierk.shoostr.example.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Timeout(20)
class McpOperationsExampleTest {
  @Test
  void runsTheToolOnTheApplicationSchedulerAndLeavesItUsableAfterShutdown() throws Exception {
    var callbacks = Schedulers.newBoundedElastic(2, 8, "application-tools");
    var bulkhead =
        Bulkhead.of(
            "status",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());

    try {
      try (var test =
              TestServer.start(
                  McpOperationsExample.application(
                      () -> Thread.currentThread().getName(), bulkhead, callbacks));
          var client =
              McpClient.sync(
                      HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                          .endpoint("/mcp")
                          .build())
                  .requestTimeout(Duration.ofSeconds(3))
                  .build()) {
        client.initialize();
        var result = client.callTool(McpSchema.CallToolRequest.builder("status").build());
        assertFalse(result.isError());
        assertTrue(
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst())
                .text()
                .startsWith("application-tools-"));
      }

      assertFalse(callbacks.isDisposed());
      assertEquals(
          "available",
          Mono.fromCallable(() -> "available").subscribeOn(callbacks).block(Duration.ofSeconds(2)));
    } finally {
      callbacks.dispose();
    }
  }

  @Test
  void rejectsExcessToolExecutionAndRecoversWithoutBlockingHealthOrClientReplies()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var callbacks = Schedulers.newBoundedElastic(2, 8, "isolated-tools");
    var bulkhead =
        Bulkhead.of(
            "status",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());

    try {
      try (var pending = Executors.newVirtualThreadPerTaskExecutor();
          var test =
              TestServer.start(
                  McpOperationsExample.application(
                      () -> {
                        if (calls.getAndIncrement() == 0) {
                          entered.countDown();
                          if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Fixture was not released");
                          }
                        }

                        return "healthy";
                      },
                      bulkhead,
                      callbacks));
          var client =
              McpClient.sync(
                      HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                          .endpoint("/mcp")
                          .build())
                  .requestTimeout(Duration.ofSeconds(5))
                  .build();
          var other =
              McpClient.sync(
                      HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                          .endpoint("/mcp")
                          .build())
                  .requestTimeout(Duration.ofSeconds(5))
                  .build()) {
        client.initialize();
        other.initialize();
        var active =
            pending.submit(
                () -> client.callTool(McpSchema.CallToolRequest.builder("status").build()));

        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var rejected = other.callTool(McpSchema.CallToolRequest.builder("status").build());
          assertTrue(rejected.isError());
          assertEquals(
              "Status is busy",
              assertInstanceOf(McpSchema.TextContent.class, rejected.content().getFirst()).text());
          assertEquals(1, calls.get());
          var health =
              test.send(request -> request.path("/health"), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, health.statusCode());
          assertEquals("healthy", health.body());
        } finally {
          release.countDown();
        }

        assertFalse(active.get(3, TimeUnit.SECONDS).isError());
        assertFalse(other.callTool(McpSchema.CallToolRequest.builder("status").build()).isError());
      }
    } finally {
      release.countDown();
      callbacks.dispose();
    }
  }

  @Test
  void returnsBulkheadCapacityWhenTheApplicationOperationFails() throws Exception {
    var calls = new AtomicInteger();
    var callbacks = Schedulers.newBoundedElastic(2, 8, "failure-tools");
    var bulkhead =
        Bulkhead.of(
            "status",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());

    try {
      try (var test =
              TestServer.start(
                  McpOperationsExample.application(
                      () -> {
                        if (calls.getAndIncrement() == 0) {
                          throw new IllegalStateException("Status unavailable");
                        }

                        return "healthy";
                      },
                      bulkhead,
                      callbacks));
          var client =
              McpClient.sync(
                      HttpClientStreamableHttpTransport.builder(test.baseUri().toString())
                          .endpoint("/mcp")
                          .build())
                  .requestTimeout(Duration.ofSeconds(3))
                  .build()) {
        client.initialize();
        var call = McpSchema.CallToolRequest.builder("status").build();
        assertThrows(McpError.class, () -> client.callTool(call));
        assertFalse(client.callTool(call).isError());
      }
    } finally {
      callbacks.dispose();
    }
  }
}
