package io.github.suppierk.shoostr.example.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestStateExampleTest {
  @Test
  void snapshotsBindingsWithoutCopyingMutableApplicationValues() throws Exception {
    var retained = new AtomicReference<Map<String, Object>>();

    try (var app = RequestStateExample.application()) {
      app.routes()
          .get(
              "/attributes",
              (request, response) -> {
                assertTrue(request.attribute("notes").isEmpty());
                assertTrue(request.attribute("Locale").isEmpty());
                var notes = new ArrayList<>(List.of("initial"));
                request.attribute("notes", notes).attribute("remove", "original");
                assertSame(notes, request.attribute("notes").orElseThrow());
                var snapshot = request.attributeMap();
                assertThrows(UnsupportedOperationException.class, snapshot::clear);

                request.attribute("notes", List.of("replacement")).attribute("remove", null);
                notes.add("later");

                assertSame(notes, snapshot.get("notes"));
                assertEquals(List.of("initial", "later"), snapshot.get("notes"));
                assertEquals("original", snapshot.get("remove"));
                assertEquals(List.of("replacement"), request.attribute("notes").orElseThrow());
                assertTrue(request.attribute("remove").isEmpty());
                retained.set(snapshot);
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        for (var attempt = 0; attempt < 2; attempt++) {
          var response =
              test.send(
                  request -> request.path("/attributes"), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("checked", response.body());
          assertEquals(List.of("initial", "later"), retained.get().get("notes"));
          assertEquals("original", retained.get().get("remove"));
        }
      }
    }
  }

  @Test
  void rejectsForeignThreadRequestAccessButRetainsMetadataSnapshots() throws Exception {
    var retained = new AtomicReference<Map<String, List<String>>>();

    try (var app = RequestStateExample.application()) {
      app.routes()
          .get(
              "/ownership",
              (request, response) -> {
                retained.set(request.headerMap());

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                  executor
                      .submit(
                          () -> {
                            assertThrows(IllegalStateException.class, request::attributeMap);
                            assertThrows(IllegalStateException.class, request::headerMap);
                            assertThrows(
                                IllegalStateException.class,
                                () -> request.attribute("locale", "bad"));
                          })
                      .get(3, TimeUnit.SECONDS);
                }

                assertEquals("en", request.attribute("locale").orElseThrow());
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/ownership").header("X-Value", "kept"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals(List.of("kept"), retained.get().get("X-VALUE"));
        assertThrows(UnsupportedOperationException.class, retained.get()::clear);

        var next =
            test.send(
                request -> request.path("/greeting").header("X-Value", "different"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, next.statusCode());
        assertEquals("Hello", next.body());
        assertEquals(List.of("kept"), retained.get().get("x-value"));
      }
    }
  }

  @Test
  void sharesHookValuesWithoutLeakingThemToTheNextRequest() throws Exception {
    try (var test = TestServer.start(RequestStateExample.application())) {
      var french =
          test.send(
              request -> request.path("/greeting?locale=fr"), HttpResponse.BodyHandlers.ofString());
      var english =
          test.send(request -> request.path("/greeting"), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, french.statusCode());
      assertEquals("Bonjour", french.body());
      assertEquals(200, english.statusCode());
      assertEquals("Hello", english.body());
      var frenchId = UUID.fromString(french.headers().firstValue("X-Request-Id").orElseThrow());
      var englishId = UUID.fromString(english.headers().firstValue("X-Request-Id").orElseThrow());
      assertNotEquals(frenchId, englishId);
    }
  }
}
