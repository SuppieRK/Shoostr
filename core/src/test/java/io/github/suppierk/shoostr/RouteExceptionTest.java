package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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
      (ignored, ignored2) -> {
        throw new IllegalArgumentException("private");
      };

  @Test
  void appliesGroupMappingToAnEarlierDescendantWithoutAnExtension() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .path(
              "/group",
              group -> {
                group.path(
                    "/child",
                    child ->
                        child.get(
                            (ignored3, ignored4) -> {
                              throw new IllegalArgumentException("private");
                            }));
                group.exception(
                    IllegalArgumentException.class,
                    (ignored5, ignored6, response) -> response.text("group"));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void usesAChildExactTypeOverrideRegardlessOfParentRegistrationOrder(boolean parentFirst)
      throws Exception {
    try (var app = new Shoostr()) {
      var root = app.routes();
      if (parentFirst) {
        root.exception(
            IllegalArgumentException.class,
            (ignored7, ignored8, response) -> response.text("parent"));
      }

      root.path(
          "/child",
          child -> {
            child.get(REJECT);
            child.exception(
                IllegalArgumentException.class,
                (ignored9, ignored10, response) -> response.text("child"));
          });
      if (!parentFirst) {
        root.exception(
            IllegalArgumentException.class,
            (ignored11, ignored12, response) -> response.text("parent"));
      }

      try (var test = TestServer.start(app)) {
        assertEquals(
            "child",
            test.send(request -> request.path("/child"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void preservesAMoreSpecificParentMappingOverABroaderChildMapping() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .exception(
              IllegalArgumentException.class,
              (ignored13, ignored14, response) -> response.text("specific"))
          .path(
              "/child",
              child ->
                  child
                      .exception(
                          Exception.class,
                          (ignored15, ignored16, response) -> response.text("broad"))
                      .get(REJECT));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "specific",
            test.send(request -> request.path("/child"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void isolatesSiblingScopesWithTheSameUrlPrefix() throws Exception {
    try (var app = new Shoostr()) {
      app.exception(
          IllegalArgumentException.class, (ignored17, ignored18, response) -> response.text("app"));
      app.routes()
          .path(
              "/shared",
              get ->
                  get.exception(
                          Exception.class, (ignored19, ignored20, response) -> response.text("get"))
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

  @Test
  void replacesAnEarlierMappingInTheSameScope() throws Exception {
    try (var app = new Shoostr()) {
      var root = app.routes();
      assertSame(
          root,
          root.exception(
              Exception.class, (ignored21, ignored22, response) -> response.text("first")));
      root.get("/fail", REJECT)
          .exception(
              Exception.class, (ignored23, ignored24, response) -> response.text("replacement"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "replacement",
            test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void usesTheAppRendererWhenNoScopedTypeMatches() throws Exception {
    try (var app = new Shoostr()) {
      app.exception(
          IllegalArgumentException.class, (ignored25, ignored26, response) -> response.text("app"));
      app.routes()
          .exception(
              IllegalStateException.class,
              (ignored27, ignored28, response) -> response.text("unrelated"))
          .get("/fail", REJECT);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "app",
            test.send(request -> request.path("/fail"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void allowsARetainedGroupToConfigureEarlierBindingsBeforeStartup() throws Exception {
    var retained = new AtomicReference<Routes>();

    try (var app = new Shoostr()) {
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
          (ignored29, ignored30, response) -> response.text("late"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "late",
            test.send(request -> request.path("/retained"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertThrows(
            IllegalStateException.class,
            () ->
                group.exception(
                    Exception.class, (ignored31, ignored32, response) -> response.text("closed")));
      }
    }
  }

  @Test
  void doesNotRetryAppRenderingWhenTheScopedRendererFails() throws Exception {
    var appInvoked = new AtomicBoolean();

    try (var app = new Shoostr()) {
      app.exception(
          Exception.class,
          (ignored33, ignored34, response) -> {
            appInvoked.set(true);
            response.text("app");
          });
      app.routes()
          .exception(
              Exception.class,
              (ignored35, ignored36, ignored37) -> {
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

  @Test
  void appliesTypedScopeMappingToAnEarlierDescendant() throws Exception {
    try (var app = new Shoostr()) {
      var typed = app.codec(new TestCodec()).routes();
      typed.path(
          "/typed",
          group -> {
            group.path(
                "/child",
                child ->
                    child.get(
                        (ignored38, ignored39) -> {
                          throw new IllegalArgumentException();
                        }));
            assertSame(
                group,
                group.exception(
                    IllegalArgumentException.class,
                    (ignored40, ignored41, response) -> response.text("typed error")));
          });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "typed error",
            test.send(request -> request.path("/typed/child"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void mapsAnSseFailureBeforeStreamCommitment() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .sse("/events", REJECT)
          .exception(
              IllegalArgumentException.class,
              (ignored42, ignored43, response) -> response.text("sse error"));

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

  @Test
  void mapsAStaticMountAdmissionFailure() throws Exception {
    Files.writeString(directory.resolve("hello.txt"), "hello");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler(REJECT);
      app.routes()
          .staticFiles("/assets", directory)
          .exception(
              IllegalArgumentException.class,
              (ignored44, ignored45, response) -> response.text("mount error"));

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

  @Test
  void mapsAWebSocketHandshakeFactoryFailure() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .websocket(
              "/socket",
              (ignored46, ignored47) -> {
                throw new IllegalArgumentException();
              })
          .exception(
              IllegalArgumentException.class,
              (ignored48, ignored49, response) -> response.status(422));

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var url = URI.create("ws:" + test.baseUri().getRawSchemeSpecificPart()).resolve("/socket");
        var failure =
            assertThrows(
                ExecutionException.class,
                () ->
                    client
                        .newWebSocketBuilder()
                        .buildAsync(url, new WebSocket.Listener() {})
                        .get(3, TimeUnit.SECONDS));
        assertEquals(
            422,
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause())
                .getResponse()
                .statusCode());
      }
    }
  }

  @Test
  void mapsAnAuthenticationFailureBeforeInvokingTheEndpoint() throws Exception {
    var authentication = new RejectingAuthentication();
    var invoked = new AtomicBoolean();

    try (var app = new Shoostr()) {
      app.authentication(authentication)
          .routes()
          .path(
              "/secure",
              group -> {
                group.get((ignored50, ignored51) -> invoked.set(true));
                group.exception(
                    AuthorizationDeniedException.class,
                    (ignored52, ignored53, response) -> response.text("denied"));
              },
              extensions -> extensions.with(authentication));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/secure"), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertEquals("denied", result.body());
        assertFalse(invoked.get());
      }
    }
  }

  @Test
  void mapsAPreCommitFlushFailureWithTheBoundScope() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .path(
              "/flush",
              group -> {
                group.get(
                    (ignored54, response) -> response.text("discard"),
                    extensions -> extensions.lifecycle().beforeResponseFlush(REJECT));
                group.exception(
                    IllegalArgumentException.class,
                    (ignored55, ignored56, response) -> response.text("flush error"));
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "flush error",
            test.send(request -> request.path("/flush"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsNullMappingInputsBeforeChangingExistingConfiguration() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      routes.exception(
          Exception.class, (ignored57, ignored58, response) -> response.text("existing"));
      assertThrows(
          NullPointerException.class,
          () -> routes.exception(null, (ignored59, ignored60, ignored61) -> {}));
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

  @Test
  void rejectsMappingRegistrationAfterClosure() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      routes.close();
      assertThrows(
          IllegalStateException.class,
          () -> routes.exception(Exception.class, (ignored62, ignored63, ignored64) -> {}));
    }
  }

  @Test
  void waitsForAnActiveGroupBeforePublishingConcurrentScopeMappings() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr()) {
      var registration =
          workers.submit(
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

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        app.routes()
            .exception(
                IllegalArgumentException.class,
                (ignored65, ignored66, response) -> response.text("concurrent mapping"));
        assertThrows(IllegalStateException.class, app::start);
      } finally {
        release.countDown();
      }

      registration.get(3, TimeUnit.SECONDS);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "concurrent mapping",
            test.send(request -> request.path("/concurrent"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  private static final class RejectingAuthentication extends AuthenticationExtension {
    @Override
    public void handle(Request request, Response response) {
      throw new AuthorizationDeniedException("Bearer error=\"insufficient_scope\"");
    }
  }
}
