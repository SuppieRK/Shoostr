package io.github.suppierk.shoostr.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.HashSet;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class OpenApiUiEqualityTest {
  @Test
  void comparesOnlyConfiguredPaths() {
    EqualsVerifier.forClass(OpenApiUi.class)
        .withNonnullFields("basePath", "specPath")
        .withIgnoredFields("resources")
        .verify();
  }

  @Test
  void resourceComparesContentTypeAndByteContents() throws ClassNotFoundException {
    EqualsVerifier.forClass(Class.forName(OpenApiUi.class.getName() + "$Resource"))
        .withNonnullFields("contentType", "bytes")
        .verify();
  }

  @Test
  void independentlyOwnedResourceArraysWithMatchingContentsCompareEqual()
      throws ReflectiveOperationException {
    var first = resource("text/plain", new byte[] {1, 2});
    var second = resource("text/plain", new byte[] {1, 2});
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, resource("application/json", new byte[] {1, 2}));
    assertNotEquals(first, resource("text/plain", new byte[] {2, 1}));
  }

  @Test
  void preparingAssetsPreservesEqualityHashMembershipAndConstrainedRouteLookup() throws Exception {
    var document = new OpenApi("Example", "1");
    var ui = document.uiHandler("/docs/", "/openapi.json");
    var sameConfiguration = new OpenApiUi("/docs/", "/openapi.json");
    assertEquals(ui, sameConfiguration);
    assertNotEquals(new OpenApiUi("/different/", "/openapi.json"), ui);
    assertNotEquals(new OpenApiUi("/docs/", "/different.json"), ui);
    var initialHash = ui.hashCode();
    var handlers = new HashSet<Handler>();
    handlers.add(ui);
    handlers.add(sameConfiguration);
    assertEquals(1, handlers.size());

    try (var app = new Shoostr().extensions(document)) {
      app.routes().get("/docs/", ui).get("/{id:[0-9]+}/first", ui).get("/{id:[0-9]+}/second", ui);

      try (var server = TestServer.start(app)) {
        assertEquals(sameConfiguration, ui);
        assertEquals(initialHash, ui.hashCode());
        assertTrue(handlers.contains(ui));
        var response =
            server.send(request -> request.path("/docs/"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("swagger-ui"));
        // Each constrained branch has multiple endpoint ranks; an unknown UI URL still returns 404.
        assertEquals(404, server.send(request -> request.path("/1/first")).statusCode());
        assertEquals(404, server.send(request -> request.path("/1/second")).statusCode());
      }
    }

    assertEquals(initialHash, ui.hashCode());
    assertTrue(handlers.remove(ui));
    assertTrue(handlers.isEmpty());
  }

  private static Object resource(String type, byte[] bytes) throws ReflectiveOperationException {
    var resource = Class.forName(OpenApiUi.class.getName() + "$Resource");
    var constructor = resource.getDeclaredConstructor(String.class, byte[].class);
    constructor.setAccessible(true);
    return constructor.newInstance(type, bytes);
  }
}
