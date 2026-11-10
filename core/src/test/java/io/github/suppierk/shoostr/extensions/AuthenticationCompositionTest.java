package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationCompositionTest {
  @Test
  void activatesAuthenticationDefaultsAndReturnsTheApplication() throws Exception {
    var first = new IdentitySource("first");

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        assertSame(app, app.withExtension(first));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsTwoRequiredIdentitySourcesWithoutPublishingTheEndpoint(boolean reverse)
      throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");
    var facts = new ArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/private",
                    (ignored, response) -> response.text("private"),
                    e -> {
                      e.onRoute((ignored2, path) -> facts.add(path));
                      e.withExtension(reverse ? second : first);
                      e.withExtension(reverse ? first : second);
                    }));

        app.routes()
            .get(
                "/private",
                (ignored3, response) -> response.text("corrected"),
                e -> {
                  e.withExtension(second);
                  e.onRoute((ignored4, path) -> facts.add(path));
                });

        try (var test = TestServer.start(app)) {
          assertEquals(List.of("/private"), facts);
          assertEquals(
              "corrected",
              test.send(request -> request.path("/private"), HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void rejectsAChildRequiringADifferentIdentitySourceThanItsParent() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {

        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.path(
                    "/private",
                    child ->
                        child.get(
                            "/child", (ignored5, ignored6) -> {}, e -> e.withExtension(second)),
                    e -> e.withExtension(first)));

        app.routes().get("/private/child", (ignored7, response) -> response.text("corrected"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "corrected",
              test.send(
                      request -> request.path("/private/child"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void authenticatesWithTheIdentitySourceSelectedByEachGroup() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {

        app.routes()
            .path(
                "/first",
                child ->
                    child.get(
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName())),
                e -> e.withExtension(first));
        app.routes()
            .path(
                "/second",
                child ->
                    child.get(
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName())),
                e -> e.withExtension(second));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "first",
              test.send(request -> request.path("/first"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "second",
              test.send(request -> request.path("/second"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(1, first.requests.get());
          assertEquals(1, second.requests.get());
        }
      }
    }
  }

  @Test
  void repeatedInheritedRequirementAuthenticatesOnce() throws Exception {
    var source = new IdentitySource("first");

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {

        app.routes()
            .path(
                "/private",
                child ->
                    child.get(
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName()),
                        e -> e.withExtension(source)),
                e -> e.withExtension(source));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "first",
              test.send(request -> request.path("/private"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(1, source.requests.get());
        }
      }
    }
  }

  @Test
  void scopedSelectionDoesNotAuthenticatePublicEndpoints() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.routes()
            .get(
                "/first",
                (request, response) -> response.text("first"),
                lifecycle -> lifecycle.withExtension(first))
            .get(
                "/second",
                (request, response) -> response.text("second"),
                lifecycle -> lifecycle.withExtension(second));
        app.routes()
            .get(
                "/metadata",
                (request, response) ->
                    response.text(request.principal().isEmpty() ? "public" : "private"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "public",
              test.send(request -> request.path("/metadata"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(0, first.requests.get());
          assertEquals(0, second.requests.get());
        }
      }
    }
  }

  @Test
  void rejectsANullIdentitySourceBeforeClaimingOwnership() throws Exception {
    var app = new Shoostr();

    try (Closeable cleanup = app::close) {
      assertThrows(NullPointerException.class, () -> app.withExtension(null));
      app.withExtension(new IdentitySource("corrected"));
    }
  }

  private static final class IdentitySource extends AuthenticationExtension {
    private final String name;
    private final List<String> events;
    private final AtomicInteger requests;

    private IdentitySource(String name) {
      this(name, new ArrayList<>());
    }

    private IdentitySource(String name, List<String> events) {
      this.name = Objects.requireNonNull(name);
      this.events = Objects.requireNonNull(events);
      requests = new AtomicInteger();
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      events.add("install-" + name);
    }

    @Override
    public void handle(Request request, Response response) {
      requests.incrementAndGet();
      request.principal(() -> name);
    }

    @Override
    public void close() {
      events.add("close-" + name);
    }
  }
}
