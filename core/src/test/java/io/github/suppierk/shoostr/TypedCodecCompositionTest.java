package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TypedCodecCompositionTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("executors")
  void invokesCodecOnSelectedExecutor(String name, Function<Set<Thread>, ExecutorService> factory)
      throws Exception {
    var workers = ConcurrentHashMap.<Thread>newKeySet();
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            assertTrue(workers.contains(Thread.currentThread()), name);
            return super.read(source, type);
          }

          @Override
          public byte[] write(Object value) throws Exception {
            assertTrue(workers.contains(Thread.currentThread()), name);
            return super.write(value);
          }
        };

    try (var executor = factory.apply(workers);
        var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post(
              "/codec",
              executor,
              (request, response) ->
                  response.body(MediaType.APPLICATION_JSON, request.body(String.class)));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/codec").method("POST").body(new byte[] {'A'}),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("\"A\"", result.body());
      }
    }
  }

  @Test
  void sharesActualExchangeWithExtensionCallbacksAndCompletesOnce() throws Exception {
    var requestSeen = new AtomicReference<Request>();
    var responseSeen = new AtomicReference<Response>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();
    var extension =
        new Extension<Void>() {
          @Override
          public void install(ApplicationCallbacks callbacks) {
            callbacks
                .onRouteMatched(
                    (request, response) -> {
                      requestSeen.set(request);
                      responseSeen.set(response);
                    })
                .afterRequest(outcomes::add);
          }
        };

    try (var app = new Shoostr()) {
      app.extensions(extension)
          .codec(new TestCodec())
          .routes()
          .get(
              "/shared",
              (request, response) -> {
                assertSame(requestSeen.get(), request);
                assertSame(responseSeen.get(), response);
                response.body(MediaType.APPLICATION_JSON, "same exchange");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/shared")).statusCode());
        var outcome = outcomes.poll(3, TimeUnit.SECONDS);
        assertEquals(200, Objects.requireNonNull(outcome).statusCode());
        assertTrue(outcomes.isEmpty());
      }
    }
  }

  @Test
  void mixesOrdinaryAndTypedHandlersInOneApp() throws Exception {
    try (var app = new Shoostr()) {
      app.routes().get("/ordinary", (_, response) -> response.text("ordinary"));
      var returned =
          app.codec(new TestCodec())
              .routes(
                  routes ->
                      routes.get(
                          "/typed", (_, response) -> response.body("application/json", "typed")));
      assertSame(app, returned);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ordinary",
            test.send(request -> request.path("/ordinary"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "\"typed\"",
            test.send(request -> request.path("/typed"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void authenticatesBeforeInvokingCodec(boolean authorized) throws Exception {
    var reads = new AtomicInteger();
    var writes = new AtomicInteger();
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) throws Exception {
            reads.incrementAndGet();
            return super.read(source, type);
          }

          @Override
          public byte[] write(Object value) throws Exception {
            writes.incrementAndGet();
            return super.write(value);
          }
        };
    var authentication =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            if (!"Bearer token".equals(request.header("Authorization").orElse(""))) {
              throw new AuthenticationRequiredException("Bearer");
            }

            request.principal(() -> "Alice");
          }
        };

    try (var app = new Shoostr()) {
      app.authentication(authentication)
          .codec(codec)
          .routes()
          .post(
              "/secured",
              (request, response) -> {
                request.body(String.class);
                response.body(
                    MediaType.APPLICATION_JSON, request.principal().orElseThrow().getName());
              },
              extensions -> extensions.get(authentication).required());

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> {
                  request.path("/secured").method("POST").body(new byte[] {'A'});
                  if (authorized) {
                    request.header("Authorization", "Bearer token");
                  }
                },
                HttpResponse.BodyHandlers.ofString());
        assertEquals(authorized ? 200 : 401, result.statusCode());
        assertEquals(authorized ? 1 : 0, reads.get());
        assertEquals(authorized ? 1 : 0, writes.get());
        if (authorized) {
          assertEquals("\"Alice\"", result.body());
        } else {
          assertEquals("Bearer", result.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
      }
    }
  }

  private static Stream<Arguments> executors() {
    Function<Set<Thread>, ExecutorService> platform =
        workers ->
            Executors.newSingleThreadExecutor(
                recordingFactory(Thread.ofPlatform().factory(), workers));
    Function<Set<Thread>, ExecutorService> virtual =
        workers ->
            Executors.newThreadPerTaskExecutor(
                recordingFactory(Thread.ofVirtual().factory(), workers));
    Function<Set<Thread>, ExecutorService> forkJoin =
        workers ->
            new ForkJoinPool(
                2,
                pool -> {
                  var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                  workers.add(thread);
                  return thread;
                },
                null,
                false);
    Function<Set<Thread>, ExecutorService> wrapped =
        workers -> Executors.unconfigurableExecutorService(platform.apply(workers));
    return Stream.of(
        Arguments.of("platform", platform),
        Arguments.of("virtual", virtual),
        Arguments.of("forkJoin", forkJoin),
        Arguments.of("wrapped", wrapped));
  }

  private static ThreadFactory recordingFactory(ThreadFactory factory, Set<Thread> workers) {
    return task -> {
      var thread = factory.newThread(task);
      workers.add(thread);
      return thread;
    };
  }
}
