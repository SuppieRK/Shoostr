package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class RouteExceptionTest {
  @TempDir Path directory;

  private static final Handler REJECT =
      (ignored, ignoredResponse) -> {
        throw new IllegalArgumentException("private");
      };

  @Test
  void appliesGroupMappingToAnEarlierDescendantWithoutAnExtension() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .path(
                "/group",
                group -> {
                  group.path(
                      "/child",
                      child ->
                          child.get(
                              (ignoredRequest, ignoredResponse) -> {
                                throw new IllegalArgumentException("private");
                              }));
                  group.exception(
                      IllegalArgumentException.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("group"));
                });

        try (var test = TestServer.start(app)) {
          var result =
              test.send(
                  request -> request.path("/group/child"), HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
          assertEquals("group", result.body());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void usesAChildExactTypeOverrideRegardlessOfParentRegistrationOrder(boolean parentFirst)
      throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        var root = app.routes();
        if (parentFirst) {
          root.exception(
              IllegalArgumentException.class,
              (ignoredFailure, ignoredRequest, response) -> response.text("parent"));
        }

        root.path(
            "/child",
            child -> {
              child.get(REJECT);
              child.exception(
                  IllegalArgumentException.class,
                  (ignoredFailure, ignoredRequest, response) -> response.text("child"));
            });
        if (!parentFirst) {
          root.exception(
              IllegalArgumentException.class,
              (ignoredFailure, ignoredRequest, response) -> response.text("parent"));
        }

        try (var test = TestServer.start(app)) {
          assertEquals(
              "child",
              test.send(request -> request.path("/child"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void preservesAMoreSpecificParentMappingOverABroaderChildMapping() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .exception(
                IllegalArgumentException.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("specific"))
            .path(
                "/child",
                child ->
                    child
                        .exception(
                            Exception.class,
                            (ignoredFailure, ignoredRequest, response) -> response.text("broad"))
                        .get(REJECT));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "specific",
              test.send(request -> request.path("/child"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void isolatesSiblingScopesWithTheSameUrlPrefix() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.exception(
            IllegalArgumentException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("app"));
        app.routes()
            .path(
                "/shared",
                get ->
                    get.exception(
                            Exception.class,
                            (ignoredFailure, ignoredRequest, response) -> response.text("get"))
                        .get(REJECT));
        app.routes().path("/shared", post -> post.post(REJECT));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "get",
              test.send(request -> request.path("/shared"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "app",
              test.send(
                      request -> request.path("/shared").method("POST"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void replacesAnEarlierMappingInTheSameScope() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        var root = app.routes();

        assertSame(
            root,
            root.exception(
                Exception.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("first")));

        root.get("/fail", REJECT)
            .exception(
                Exception.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("replacement"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "replacement",
              test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void usesTheAppRendererWhenNoScopedTypeMatches() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.exception(
            IllegalArgumentException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("app"));
        app.routes()
            .exception(
                IllegalStateException.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("unrelated"))
            .get("/fail", REJECT);

        try (var test = TestServer.start(app)) {
          assertEquals(
              "app",
              test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void allowsARetainedGroupToConfigureEarlierBindingsBeforeStartup() throws Exception {
    var retained = new AtomicReference<Routes>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .path(
                "/retained",
                group -> {
                  retained.set(group);
                  group.get(REJECT);
                });
        var group = retained.get();
        group.exception(
            IllegalArgumentException.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("late"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "late",
              test.send(request -> request.path("/retained"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertThrows(
              IllegalStateException.class,
              () ->
                  group.exception(
                      Exception.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("closed")));
        }
      }
    }
  }

  @Test
  void doesNotRetryAppRenderingWhenTheScopedRendererFails() throws Exception {
    var appInvoked = new AtomicBoolean();

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.exception(
            Exception.class,
            (ignoredFailure, ignoredRequest, response) -> {
              appInvoked.set(true);
              response.text("app");
            });
        app.routes()
            .exception(
                Exception.class,
                (ignoredFailure, ignoredRequest, ignoredResponse) -> {
                  throw new IllegalStateException("renderer failed");
                })
            .get("/fail", REJECT);

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString());

          assertEquals(500, result.statusCode());
          assertFalse(result.body().contains("renderer failed"));
          assertFalse(appInvoked.get());
        }
      }
    }
  }

  @Test
  void appliesTypedScopeMappingToAnEarlierDescendant() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        var typed = app.codec(new TestCodec()).routes();
        typed.path(
            "/typed",
            group -> {
              group.path(
                  "/child",
                  child ->
                      child.get(
                          (ignoredTypedRequest, ignoredTypedResponse) -> {
                            throw new IllegalArgumentException();
                          }));

              assertSame(
                  group,
                  group.exception(
                      IllegalArgumentException.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("typed error")));
            });

        try (var test = TestServer.start(app)) {
          assertEquals(
              "typed error",
              test.send(
                      request -> request.path("/typed/child"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void mapsAnSseFailureBeforeStreamCommitment() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.routes()
            .sse("/events", REJECT)
            .exception(
                IllegalArgumentException.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("sse error"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "sse error",
              test.send(
                      request -> request.path("/events").header("Accept", "text/event-stream"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void mapsAStaticMountAdmissionFailure() throws Exception {
    Files.writeString(directory.resolve("hello.txt"), "hello");

    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.beforeRouteHandler(REJECT);
        app.routes()
            .staticFiles("/assets", directory)
            .exception(
                IllegalArgumentException.class,
                (ignoredFailure, ignoredRequest, response) -> response.text("mount error"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "mount error",
              test.send(
                      request -> request.path("/assets/hello.txt"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void mapsAWebSocketHandshakeFactoryFailure() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.routes()
            .websocket(
                "/socket",
                (ignoredRequest, ignoredServerUpgradeResponse) -> {
                  throw new IllegalArgumentException();
                })
            .exception(
                IllegalArgumentException.class,
                (ignoredFailure, ignoredRequest, response) -> response.status(422));

        try (var test = TestServer.start(app);
            var client = HttpClient.newHttpClient()) {
          var url =
              URI.create("ws:" + test.baseUri().getRawSchemeSpecificPart()).resolve("/socket");
          var failure = assertFailureForMapsAWebSocketHandshakeFactoryFailure(client, url);

          assertEquals(
              422,
              assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
                  .getResponse()
                  .statusCode());
        }
      }
    }
  }

  private ExecutionException assertFailureForMapsAWebSocketHandshakeFactoryFailure(
      HttpClient client, URI url) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(url, new WebSocket.Listener() {})
                .get(3, TimeUnit.SECONDS));
  }

  @Test
  void mapsAnAuthenticationFailureBeforeInvokingTheEndpoint() throws Exception {
    var authentication = new RejectingAuthentication();
    var invoked = new AtomicBoolean();

    {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        app.routes()
            .path(
                "/secure",
                group -> {
                  group.get((ignoredRequest, ignoredResponse) -> invoked.set(true));
                  group.exception(
                      AuthorizationDeniedException.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("denied"));
                },
                extensions -> extensions.withExtension(authentication));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/secure"), HttpResponse.BodyHandlers.ofString());

          assertEquals(403, result.statusCode());
          assertEquals("denied", result.body());
          assertFalse(invoked.get());
        }
      }
    }
  }

  @Test
  void mapsAPreCommitFlushFailureWithTheBoundScope() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup13 = app::close) {
        app.routes()
            .path(
                "/flush",
                group -> {
                  group.get(
                      (ignoredRequest, response) -> response.text("discard"),
                      extensions -> extensions.beforeResponseFlush(REJECT));
                  group.exception(
                      IllegalArgumentException.class,
                      (ignoredFailure, ignoredRequest, response) -> response.text("flush error"));
                });

        try (var test = TestServer.start(app)) {
          assertEquals(
              "flush error",
              test.send(request -> request.path("/flush"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void rejectsNullMappingInputsBeforeChangingExistingConfiguration() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        var routes = app.routes();
        routes.exception(
            Exception.class,
            (ignoredFailure, ignoredRequest, response) -> response.text("existing"));

        assertThrows(
            NullPointerException.class,
            () -> routes.exception(null, (ignoredFailure, ignoredRequest, ignoredResponse) -> {}));
        assertThrows(NullPointerException.class, () -> routes.exception(Exception.class, null));

        routes.get("/fail", REJECT);

        try (var test = TestServer.start(app)) {
          assertEquals(
              "existing",
              test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void rejectsMappingRegistrationAfterClosure() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        var routes = app.routes();
        app.close();

        assertThrows(
            IllegalStateException.class,
            () ->
                routes.exception(
                    Exception.class, (ignoredFailure, ignoredRequest, ignoredResponse) -> {}));
      }
    }
  }

  @Test
  void waitsForAnActiveGroupBeforePublishingConcurrentScopeMappings() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var workers = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        var registration =
            submitRegistrationForWaitsForAnActiveGroupBeforePublishingConcurrentScopeMappings(
                workers, app, entered, release);

        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));

          app.routes()
              .exception(
                  IllegalArgumentException.class,
                  (ignoredFailure, ignoredRequest, response) ->
                      response.text("concurrent mapping"));

          assertThrows(IllegalStateException.class, app::start);
        } finally {
          release.countDown();
        }

        registration.get(3, TimeUnit.SECONDS);

        try (var test = TestServer.start(app)) {
          assertEquals(
              "concurrent mapping",
              test.send(
                      request -> request.path("/concurrent"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  private Future<Routes>
      submitRegistrationForWaitsForAnActiveGroupBeforePublishingConcurrentScopeMappings(
          ExecutorService workers, Shoostr app, CountDownLatch entered, CountDownLatch release) {
    return workers.submit(
        () ->
            app.routes()
                .path(
                    "/concurrent",
                    group -> {
                      group.get(REJECT);
                      entered.countDown();

                      try {
                        if (!release.await(3, TimeUnit.SECONDS)) {
                          throw new IllegalStateException("Registration release timed out");
                        }
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                      }
                    }));
  }

  private static final class RejectingAuthentication extends AuthenticationExtension {
    @Override
    public void handle(Request request, Response response) {
      throw new AuthorizationDeniedException("Bearer error=\"insufficient_scope\"");
    }
  }
}
