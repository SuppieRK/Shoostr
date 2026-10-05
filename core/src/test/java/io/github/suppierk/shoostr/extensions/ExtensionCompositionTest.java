package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionCompositionTest {
  @Test
  void inheritsIndependentMetadataAndAuthenticationWithoutReplayingGroupConfiguration()
      throws Exception {
    var calls = new AtomicInteger();
    var groupCalls = new AtomicInteger();
    var auth =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            calls.incrementAndGet();
            request.principal(() -> "alice");
          }
        };
    var docs = new Documentation();
    var otherDocs = new Documentation();
    var retained = new AtomicReference<Description>();
    Handler shared =
        (request, response) -> response.text(request.principal().orElseThrow().getName());

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertSame(app, app.authentication(auth));
      assertSame(app, app.extensions(docs, otherDocs));
      app.routes(
          routes -> {
            routes.path(
                "/users",
                users -> {
                  users.get("/{id}", shared, e -> retained.set(e.get(docs).summary("One user")));
                  users.get("/search", shared);
                },
                e -> {
                  groupCalls.incrementAndGet();
                  e.get(auth).required();
                  e.get(docs).tag("users").summary("Users");
                  e.get(otherDocs).tag("public").summary("Other document");
                });
            routes.get(
                "/openapi.json",
                docs.handler(),
                e -> {
                  e.get(auth).required();
                  e.get(docs).summary("API description");
                });
          });

      try (var test = TestServer.start(app)) {

        assertEquals(1, groupCalls.get());
        assertEquals(
            "alice",
            test.send(request -> request.path("/users/123"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "alice",
            test.send(
                    request -> request.path("/users/search"), HttpResponse.BodyHandlers.ofString())
                .body());
        retained.get().summary("Changed after startup");
        assertEquals(
            "GET /users/{id} users One user\nGET /users/search users Users\nGET /openapi.json  API description",
            test.send(
                    request -> request.path("/openapi.json"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(3, calls.get());
        assertEquals(
            List.of(
                "GET /users/{id} public Other document", "GET /users/search public Other document"),
            otherDocs.operations);
      }
    }
  }

  private static final class Documentation implements Extension<Description> {
    private final List<String> operations;

    private Documentation() {
      operations = new ArrayList<>();
    }

    @Override
    public Description configure(Extensions endpoint, Description inherited) {
      var description = new Description(inherited);
      assertSame(
          endpoint,
          endpoint.onRoute(
              (method, path) ->
                  operations.add(
                      method.value()
                          + " "
                          + path
                          + " "
                          + description.tag
                          + " "
                          + description.summary)));
      return description;
    }

    Handler handler() {
      return (_, response) -> response.text(String.join("\n", operations));
    }
  }

  private static final class Description {
    private String tag;
    private String summary;

    private Description(Description inherited) {
      tag = inherited == null ? "" : inherited.tag;
      summary = inherited == null ? "" : inherited.summary;
    }

    Description tag(String value) {
      tag = value;
      return this;
    }

    Description summary(String value) {
      summary = value;
      return this;
    }
  }
}
