package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
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
  void installsMultipleIdentitySourcesAndReturnsTheApplication() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    try (var app = new Shoostr()) {
      assertSame(app, app.authentication(first, second));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsTwoRequiredIdentitySourcesWithoutPublishingTheEndpoint(boolean reverse)
      throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");
    var facts = new ArrayList<String>();

    try (var app = new Shoostr()) {
      app.authentication(first, second);
      var routes = app.routes();

      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/private",
                  (_, response) -> response.text("private"),
                  e -> {
                    e.onRoute((_, path) -> facts.add(path));
                    e.get(reverse ? second : first).required();
                    e.get(reverse ? first : second).required();
                  }));

      app.routes()
          .get(
              "/private",
              (_, response) -> response.text("corrected"),
              e -> {
                e.get(second).required();
                e.onRoute((_, path) -> facts.add(path));
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

  @Test
  void rejectsAChildRequiringADifferentIdentitySourceThanItsParent() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    try (var app = new Shoostr()) {
      app.authentication(first, second);
      var routes = app.routes();

      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.path(
                  "/private",
                  child -> child.get("/child", (_, _) -> {}, e -> e.get(second).required()),
                  e -> e.get(first).required()));

      app.routes().get("/private/child", (_, response) -> response.text("corrected"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "corrected",
            test.send(
                    request -> request.path("/private/child"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void authenticatesWithTheIdentitySourceSelectedByEachGroup() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    try (var app = new Shoostr()) {
      app.authentication(first, second);
      app.routes()
          .path(
              "/first",
              child ->
                  child.get(
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName()),
                      e -> e.get(second)),
              e -> e.get(first).required());
      app.routes()
          .path(
              "/second",
              child ->
                  child.get(
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName())),
              e -> e.get(second).required());

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first",
            test.send(request -> request.path("/first"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "second",
            test.send(request -> request.path("/second"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void repeatedInheritedRequirementAuthenticatesOnce() throws Exception {
    var source = new IdentitySource("first");

    try (var app = new Shoostr()) {
      app.authentication(source);
      app.routes()
          .path(
              "/private",
              child ->
                  child.get(
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName()),
                      e -> e.get(source).required()),
              e -> e.get(source).required());

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first",
            test.send(request -> request.path("/private"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(1, source.requests.get());
      }
    }
  }

  @Test
  void installationDoesNotAuthenticatePublicEndpoints() throws Exception {
    var first = new IdentitySource("first");
    var second = new IdentitySource("second");

    try (var app = new Shoostr()) {
      app.authentication(first, second);
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

  @Test
  void nullAdditionalArrayInstallsAndClosesOnlyTheFirstIdentitySource() throws Exception {
    var events = new ArrayList<String>();
    var first = new IdentitySource("first", events);

    try (var app = new Shoostr()) {
      assertSame(app, app.authentication(first, (AuthenticationExtension[]) null));
      assertEquals(List.of("install-first"), events);
    }

    assertEquals(List.of("install-first", "close-first"), events);
  }

  @Test
  void rejectsNullIdentitySourcesBeforeInstallingAnyInstance() throws Exception {
    var events = new ArrayList<String>();
    var first = new IdentitySource("first", events);
    var second = new IdentitySource("second", events);

    try (var app = new Shoostr()) {
      assertThrows(NullPointerException.class, () -> app.authentication(null, second));
      assertThrows(NullPointerException.class, () -> app.authentication(first, second, null));
      assertTrue(events.isEmpty());
      app.authentication(first, second);
    }

    assertEquals(List.of("install-first", "install-second", "close-second", "close-first"), events);
  }

  @Test
  void rejectsDuplicateIdentitySourcesBeforeInstallingAnyInstance() throws Exception {
    var events = new ArrayList<String>();
    var first = new IdentitySource("first", events);
    var second = new IdentitySource("second", events);

    try (var app = new Shoostr()) {
      assertThrows(IllegalArgumentException.class, () -> app.authentication(first, second, first));
      assertTrue(events.isEmpty());
      app.authentication(first, second);
    }

    assertEquals(List.of("install-first", "install-second", "close-second", "close-first"), events);
  }

  @Test
  void aPreviouslyInstalledInstanceRejectsTheWholeNewBatch() throws Exception {
    var events = new ArrayList<String>();
    var first = new IdentitySource("first", events);
    var second = new IdentitySource("second", events);

    try (var app = new Shoostr()) {
      app.authentication(first);
      assertThrows(IllegalArgumentException.class, () -> app.authentication(second, first));
      assertEquals(List.of("install-first"), events);
      app.authentication(second);
    }

    assertEquals(List.of("install-first", "install-second", "close-second", "close-first"), events);
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
