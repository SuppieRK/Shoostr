package io.github.suppierk.shoostr.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.openapi.OpenApi;
import io.github.suppierk.shoostr.testing.TestServer;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

class OpenApiConsumerTest {
  @Test
  void consumesPublishedGeneratorAndPackagedUiWithoutARuntimeWebjar() throws Exception {
    var docs = new OpenApi("Consumer", "1");
    var ui = docs.uiHandler("/docs/", "/openapi.json");

    try (var app = new Shoostr()) {
      app.extensions(docs)
          .routes(
              routes -> {
                routes.get(
                    "/hello",
                    (_, response) -> response.text("hello"),
                    e -> e.get(docs).summary("Hello"));
                routes.get("/docs/", ui);
                routes.get("/docs/{*asset}", ui);
                routes.get("/openapi.json", docs.handler());
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            "Consumer",
            Json31.mapper()
                .readTree(test.send(request -> request.path("/openapi.json")).body())
                .path("info")
                .path("title")
                .asText());
        assertEquals(
            200, test.send(request -> request.path("/docs/swagger-ui-bundle.js")).statusCode());
      }
    }
  }
}
