package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointTracingTest {
  @ParameterizedTest(name = "{0}, appWide={1}, virtualApplication={2}")
  @CsvSource({
    "platform,false,false",
    "platform,false,true",
    "platform,true,false",
    "platform,true,true",
    "virtual,false,false",
    "virtual,false,true",
    "virtual,true,false",
    "virtual,true,true",
    "fork-join,false,false",
    "fork-join,false,true",
    "fork-join,true,false",
    "fork-join,true,true",
    "fork-join-2,false,false",
    "fork-join-2,false,true",
    "fork-join-2,true,false",
    "fork-join-2,true,true",
    "wrapped-fork-join,false,false",
    "wrapped-fork-join,false,true",
    "wrapped-fork-join,true,false",
    "wrapped-fork-join,true,true",
    "context-wrapped-fork-join,false,false",
    "context-wrapped-fork-join,false,true",
    "context-wrapped-fork-join,true,false",
    "context-wrapped-fork-join,true,true"
  })
  void preservesServerSpanAndRemoteParentAcrossExecutorHandoff(
      String executorType, boolean appWide, boolean virtualApplication) throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var handled = new CompletableFuture<Thread>();
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<Void>();
    var workers = executor(executorType, owned);

    try (var provider = openTracerProvider(exporter)) {
      var telemetry = createTelemetry(provider);
      Handler endpoint =
          createEndpointForPreservesServerSpanAndRemoteParentAcrossExecutorHandoff(
              handled, telemetry);

      try (workers) {
        var app = withExecutionMode(new Options(), virtualApplication);

        try (Closeable appCleanup = app::close) {
          var tracing = new OpenTelemetryTracing(telemetry);
          if (appWide) {
            app.withExtension(tracing);
          }

          app.afterRequest(ignoredRequestOutcome -> completed.complete(null));
          app.routes()
              .get("/trace", workers, endpoint, lifecycle -> lifecycle.withExtension(tracing));

          try (var test = TestServer.start(app)) {
            var response =
                sendResponseForPreservesServerSpanAndRemoteParentAcrossExecutorHandoff(test);

            assertEquals("12345678901234567890123456789012", response.body());
            assertTrue(owned.contains(handled.get(5, TimeUnit.SECONDS)), executorType);

            completed.get(5, TimeUnit.SECONDS);
            var spans = exporter.getFinishedSpanItems();

            assertEquals(2, spans.size());

            var server =
                spans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER)
                    .findFirst()
                    .orElseThrow();
            var child =
                spans.stream()
                    .filter(span -> "child".equals(span.getName()))
                    .findFirst()
                    .orElseThrow();

            assertEquals("0123456789012345", server.getParentSpanId());
            assertEquals(server.getSpanId(), child.getParentSpanId());
            assertEquals(server.getTraceId(), child.getTraceId());
            assertFalse(
                workers
                    .submit(() -> Span.current().getSpanContext().isValid())
                    .get(5, TimeUnit.SECONDS));
          }
        }
      }
    }
  }

  private Handler createEndpointForPreservesServerSpanAndRemoteParentAcrossExecutorHandoff(
      CompletableFuture<Thread> handled, OpenTelemetrySdk telemetry) {
    return (ignored, response) -> {
      handled.complete(Thread.currentThread());
      var child = telemetry.getTracer("test").spanBuilder("child").startSpan();
      child.end();
      response.text(Span.current().getSpanContext().getTraceId());
    };
  }

  private HttpResponse<String>
      sendResponseForPreservesServerSpanAndRemoteParentAcrossExecutorHandoff(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/trace")
                .header("traceparent", "00-12345678901234567890123456789012-0123456789012345-01"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({
    "1,false,false",
    "1,false,true",
    "1,true,false",
    "1,true,true",
    "2,false,false",
    "2,false,true",
    "2,true,false",
    "2,true,true"
  })
  void keepsConcurrentForkJoinRequestTracesSeparate(
      int parallelism, boolean appWide, boolean virtualApplication) throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CountDownLatch(20);

    try (var provider = openTracerProvider(exporter);
        var workers = new ForkJoinPool(parallelism);
        var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup1 = app::close) {
        var telemetry = createTelemetry(provider);
        var tracing = new OpenTelemetryTracing(telemetry);
        if (appWide) {
          app.withExtension(tracing);
        }

        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes()
            .get(
                "/trace",
                workers,
                (ignoredRequest, response) ->
                    response.text(Span.current().getSpanContext().getTraceId()),
                lifecycle -> lifecycle.withExtension(tracing));

        try (var test = TestServer.start(app)) {
          var sent = new ArrayList<Future<HttpResponse<String>>>();
          for (int index = 0; index < 20; index++) {
            var trace =
                index % 2 == 0
                    ? "11111111111111111111111111111111"
                    : "22222222222222222222222222222222";
            sent.add(
                drivers.submit(
                    () ->
                        test.send(
                            request ->
                                request
                                    .path("/trace")
                                    .header("traceparent", "00-" + trace + "-0123456789012345-01"),
                            HttpResponse.BodyHandlers.ofString())));
          }
          for (int index = 0; index < sent.size(); index++) {
            assertEquals(
                index % 2 == 0
                    ? "11111111111111111111111111111111"
                    : "22222222222222222222222222222222",
                sent.get(index).get(5, TimeUnit.SECONDS).body());
          }

          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(20, exporter.getFinishedSpanItems().size());
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void restoresOuterRequestTraceAfterHelpingAnotherMatchedRequest(
      boolean appWide, boolean virtualApplication) throws Exception {
    var entered = new CountDownLatch(1);
    var allowHelping = new CountDownLatch(1);
    var completed = new CountDownLatch(2);
    var exporter = InMemorySpanExporter.create();

    try (var provider = openTracerProvider(exporter);
        var workers = new ForkJoinPool(1);
        var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup2 = app::close) {
        var telemetry = createTelemetry(provider);
        var tracing = new OpenTelemetryTracing(telemetry);
        if (appWide) {
          app.withExtension(tracing);
        }

        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes()
            .get(
                "/outer",
                workers,
                (ignoredRequest, response) -> {
                  entered.countDown();
                  if (!allowHelping.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Helping was not enabled");
                  }

                  assertTrue(workers.awaitQuiescence(5, TimeUnit.SECONDS));

                  response.text(Span.current().getSpanContext().getTraceId());
                },
                lifecycle -> lifecycle.withExtension(tracing))
            .get(
                "/inner",
                workers,
                (ignoredRequest, response) ->
                    response.text(Span.current().getSpanContext().getTraceId()),
                lifecycle -> lifecycle.withExtension(tracing));

        try (var test = TestServer.start(app)) {
          var outer =
              submitOuterForRestoresOuterRequestTraceAfterHelpingAnotherMatchedRequest(
                  drivers, test);

          try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var inner =
                submitInnerForRestoresOuterRequestTraceAfterHelpingAnotherMatchedRequest(
                    drivers, test);
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> workers.getQueuedSubmissionCount() > 0);
            allowHelping.countDown();

            assertEquals("22222222222222222222222222222222", inner.get(5, TimeUnit.SECONDS).body());
            assertEquals("11111111111111111111111111111111", outer.get(5, TimeUnit.SECONDS).body());
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(2, exporter.getFinishedSpanItems().size());
            assertFalse(
                workers
                    .submit(() -> Span.current().getSpanContext().isValid())
                    .get(5, TimeUnit.SECONDS));
          } finally {
            allowHelping.countDown();
          }
        }
      }
    }
  }

  private Future<HttpResponse<String>>
      submitOuterForRestoresOuterRequestTraceAfterHelpingAnotherMatchedRequest(
          ExecutorService drivers, TestServer test) {
    return drivers.submit(
        () ->
            test.send(
                request ->
                    request
                        .path("/outer")
                        .header(
                            "traceparent",
                            "00-11111111111111111111111111111111-0123456789012345-01"),
                HttpResponse.BodyHandlers.ofString()));
  }

  private Future<HttpResponse<String>>
      submitInnerForRestoresOuterRequestTraceAfterHelpingAnotherMatchedRequest(
          ExecutorService drivers, TestServer test) {
    return drivers.submit(
        () ->
            test.send(
                request ->
                    request
                        .path("/inner")
                        .header(
                            "traceparent",
                            "00-22222222222222222222222222222222-0123456789012345-01"),
                HttpResponse.BodyHandlers.ofString()));
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void closesForkJoinTraceOnceAfterMappedHandlerFailure(boolean appWide, boolean virtualApplication)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<RequestOutcome>();

    try (var provider = openTracerProvider(exporter);
        var workers = new ForkJoinPool(1)) {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup3 = app::close) {
        var telemetry = createTelemetry(provider);
        var tracing = new OpenTelemetryTracing(telemetry);
        if (appWide) {
          app.withExtension(tracing);
        }

        app.afterRequest(completed::complete);
        app.routes()
            .path(
                "/failure",
                routes -> {
                  routes.exception(
                      IllegalArgumentException.class,
                      (ignoredFailure, ignoredRequest, response) ->
                          response.status(422).text(Span.current().getSpanContext().getTraceId()));
                  routes.get(
                      workers,
                      (ignoredRequest, ignoredResponse) -> {
                        throw new IllegalArgumentException("failed");
                      });
                },
                lifecycle -> lifecycle.withExtension(tracing));

        try (var test = TestServer.start(app)) {
          var response = sendResponseForClosesForkJoinTraceOnceAfterMappedHandlerFailure(test);

          assertEquals(422, response.statusCode());
          assertEquals("11111111111111111111111111111111", response.body());
          assertInstanceOf(
              IllegalArgumentException.class,
              completed.get(5, TimeUnit.SECONDS).applicationFailure());
          assertEquals(1, exporter.getFinishedSpanItems().size());
          assertFalse(
              workers
                  .submit(() -> Span.current().getSpanContext().isValid())
                  .get(5, TimeUnit.SECONDS));
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForClosesForkJoinTraceOnceAfterMappedHandlerFailure(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/failure")
                .header("traceparent", "00-11111111111111111111111111111111-0123456789012345-01"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void reattachesServerSpanForRejectedSubmissionAndCompletesItOnce(
      boolean appWide, boolean virtualApplication) throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<RequestOutcome>();
    var invoked = new AtomicBoolean();

    try (var provider = openTracerProvider(exporter);
        var workers = new ForkJoinPool(1)) {
      var app = withExecutionMode(new Options(), virtualApplication);

      try (Closeable appCleanup4 = app::close) {
        var telemetry = createTelemetry(provider);
        var tracing = new OpenTelemetryTracing(telemetry);
        if (appWide) {
          app.withExtension(tracing);
        }

        app.afterRequest(completed::complete);
        app.routes()
            .path(
                "/rejected",
                routes -> {
                  routes.exception(
                      RejectedExecutionException.class,
                      (ignoredFailure, ignoredRequest, response) ->
                          response.status(503).text(Span.current().getSpanContext().getTraceId()));
                  routes.get(workers, (ignoredRequest, ignoredResponse) -> invoked.set(true));
                },
                lifecycle -> lifecycle.withExtension(tracing));

        try (var test = TestServer.start(app)) {
          workers.shutdown();
          var response =
              sendResponseForReattachesServerSpanForRejectedSubmissionAndCompletesItOnce(test);

          assertEquals(503, response.statusCode());
          assertEquals("11111111111111111111111111111111", response.body());
          assertInstanceOf(
              RejectedExecutionException.class,
              completed.get(5, TimeUnit.SECONDS).applicationFailure());
          assertFalse(invoked.get());

          var spans = exporter.getFinishedSpanItems();

          assertEquals(1, spans.size());
          assertEquals("0123456789012345", spans.getFirst().getParentSpanId());
          assertEquals(SpanKind.SERVER, spans.getFirst().getKind());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResponseForReattachesServerSpanForRejectedSubmissionAndCompletesItOnce(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/rejected")
                .header("traceparent", "00-11111111111111111111111111111111-0123456789012345-01"),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({
    "false,false,false",
    "false,false,true",
    "false,true,false",
    "false,true,true",
    "true,false,false",
    "true,false,true",
    "true,true,false",
    "true,true,true"
  })
  void completesForkJoinTraceExactlyOnceWhenClosingWithQueuedOrRunningRequest(
      boolean queued, boolean appWide, boolean virtualApplication) throws Exception {
    var exporter = InMemorySpanExporter.create();
    var blocked = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var invoked = new AtomicBoolean();
    var restoredTrace = new CompletableFuture<String>();
    var completed = new CompletableFuture<RequestOutcome>();
    var completions = new AtomicInteger();

    try (var provider = openTracerProvider(exporter);
        var workers = new ForkJoinPool(1);
        var drivers = Executors.newVirtualThreadPerTaskExecutor()) {
      var app =
          withExecutionMode(new Options(), virtualApplication)
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close) {
        var telemetry = createTelemetry(provider);
        app.modifyServer(server -> server.setStopTimeout(0));
        var tracing = new OpenTelemetryTracing(telemetry);
        if (appWide) {
          app.withExtension(tracing);
        }

        app.afterRequest(
            outcome -> {
              completions.incrementAndGet();
              completed.complete(outcome);
            });
        app.routes()
            .get(
                "/closing",
                workers,
                (ignoredRequest, ignoredResponse) -> {
                  invoked.set(true);
                  entered.countDown();
                  if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Handler was not released");
                  }

                  restoredTrace.complete(Span.current().getSpanContext().getTraceId());
                },
                lifecycle -> lifecycle.withExtension(tracing));
        Future<Boolean> blocker = null;
        if (queued) {
          blocker =
              workers.submit(
                  () -> {
                    blocked.countDown();

                    return release.await(10, TimeUnit.SECONDS);
                  });

          assertTrue(blocked.await(5, TimeUnit.SECONDS));
        }

        try (var test = TestServer.start(app)) {
          var sent =
              submitSentForCompletesForkJoinTraceExactlyOnceWhenClosingWithQueuedOrRunningRequest(
                  drivers, test);

          try {
            if (queued) {
              await()
                  .atMost(Duration.ofSeconds(5))
                  .until(() -> workers.getQueuedSubmissionCount() > 0);
            } else {
              assertTrue(entered.await(5, TimeUnit.SECONDS));
            }

            app.close();
            if (queued) {
              assertInstanceOf(
                  RejectedExecutionException.class,
                  completed.get(5, TimeUnit.SECONDS).applicationFailure());
              assertFalse(invoked.get());
            } else {
              assertTrue(exporter.getFinishedSpanItems().isEmpty());
              assertFalse(completed.isDone());
            }
          } finally {
            release.countDown();
          }

          if (blocker != null) {
            assertTrue(blocker.get(5, TimeUnit.SECONDS));
          } else {
            assertEquals(
                "11111111111111111111111111111111", restoredTrace.get(5, TimeUnit.SECONDS));
          }

          completed.get(5, TimeUnit.SECONDS);

          assertEquals(1, completions.get());

          var spans = exporter.getFinishedSpanItems();

          assertEquals(1, spans.size());
          assertEquals("0123456789012345", spans.getFirst().getParentSpanId());
          assertEquals(SpanKind.SERVER, spans.getFirst().getKind());
          assertFalse(
              workers
                  .submit(() -> Span.current().getSpanContext().isValid())
                  .get(5, TimeUnit.SECONDS));
          assertEquals(!queued, invoked.get());

          try {
            sent.get(5, TimeUnit.SECONDS);
          } catch (ExecutionException failure) {
            assertInstanceOf(IOException.class, failure.getCause());
          }
        } finally {
          release.countDown();
        }
      }
    }
  }

  private Future<HttpResponse<byte[]>>
      submitSentForCompletesForkJoinTraceExactlyOnceWhenClosingWithQueuedOrRunningRequest(
          ExecutorService drivers, TestServer test) {
    return drivers.submit(
        () ->
            test.send(
                request ->
                    request
                        .path("/closing")
                        .header(
                            "traceparent",
                            "00-11111111111111111111111111111111-0123456789012345-01")));
  }

  private SdkTracerProvider openTracerProvider(InMemorySpanExporter exporter) {
    return tracerProviderProperties(exporter).build();
  }

  private SdkTracerProviderBuilder tracerProviderProperties(InMemorySpanExporter exporter) {
    return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
  }

  private OpenTelemetrySdk createTelemetry(SdkTracerProvider provider) {
    return telemetryProperties(provider).build();
  }

  private OpenTelemetrySdkBuilder telemetryProperties(SdkTracerProvider provider) {
    return OpenTelemetrySdk.builder()
        .setTracerProvider(provider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
  }

  private static ExecutorService executor(String type, Set<Thread> owned) {
    ThreadFactory factory = createFactoryForExecutor(type, owned);

    return switch (type) {
      case "platform" -> Executors.newFixedThreadPool(1, factory);
      case "virtual" -> Executors.newThreadPerTaskExecutor(factory);
      case "fork-join" -> forkJoin(1, owned);
      case "fork-join-2" -> forkJoin(2, owned);
      case "context-wrapped-fork-join" -> Context.taskWrapping(forkJoin(1, owned));
      case "wrapped-fork-join" -> Executors.unconfigurableExecutorService(forkJoin(1, owned));
      default -> throw new IllegalArgumentException(type);
    };
  }

  private static ThreadFactory createFactoryForExecutor(String type, Set<Thread> owned) {
    return task -> {
      var thread =
          "virtual".equals(type)
              ? Thread.ofVirtual().unstarted(task)
              : Thread.ofPlatform().unstarted(task);
      owned.add(thread);

      return thread;
    };
  }

  private static ForkJoinPool forkJoin(int parallelism, Set<Thread> owned) {
    return new ForkJoinPool(
        parallelism,
        pool -> {
          var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
          owned.add(worker);

          return worker;
        },
        null,
        false);
  }

  private static Shoostr withExecutionMode(Options options, boolean virtualApplication) {
    options.setVirtualThreads(virtualApplication);

    return new Shoostr(options)
        .onRequestHeaders(
            (request, response) ->
                assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }
}
