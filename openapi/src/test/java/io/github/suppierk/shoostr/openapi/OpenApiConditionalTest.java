package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiConditionalTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void documentsNestedAndTypedConditionalBindingsWithoutEvaluatingAvailability(boolean available)
      throws Exception {
    var calls = new AtomicInteger();
    var facts = new ArrayList<String>();
    var docs = new OpenApi("API", "1");
    var codec =
        new Codec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) {
            return type.cast(new String(source, StandardCharsets.UTF_8));
          }

          @Override
          public byte[] write(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.path(
                    "/api",
                    group -> {
                      group.get("/public", (_, _) -> {});
                      group.when(
                          () -> {
                            calls.incrementAndGet();
                            return available;
                          },
                          conditional ->
                              conditional.path(
                                  "/nested", nested -> nested.get("/private", (_, _) -> {})));
                    },
                    e -> {
                      e.get(docs).summary("Default");
                      e.onRoute((method, path) -> facts.add(method.value() + " " + path));
                    });
                routes.get("/openapi.json", docs.handler());
              });
      app.codec(codec)
          .routes(
              routes ->
                  routes.when(
                      () -> {
                        calls.incrementAndGet();
                        return available;
                      },
                      conditional ->
                          conditional.get(
                              "/typed", (_, _) -> {}, e -> e.get(docs).summary("Typed"))));

      try (var test = TestServer.start(app)) {
        var document =
            Json31.mapper().readTree(test.send(request -> request.path("/openapi.json")).body());
        assertEquals(0, calls.get());
        assertEquals(List.of("GET /api/public", "GET /api/nested/private"), facts);
        assertEquals(3, document.path("paths").size());
        assertTrue(document.path("paths").has("/api/public"));
        assertTrue(document.path("paths").has("/api/nested/private"));
        assertTrue(document.path("paths").has("/typed"));
        test.send(request -> request.path("/openapi.json"));
        assertEquals(0, calls.get());
        assertEquals(
            available ? 200 : 404,
            test.send(request -> request.path("/api/nested/private")).statusCode());
        assertEquals(
            available ? 200 : 404, test.send(request -> request.path("/typed")).statusCode());
      }
    }
  }
}
