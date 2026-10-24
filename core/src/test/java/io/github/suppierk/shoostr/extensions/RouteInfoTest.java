package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.RouteInfo;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RouteInfoTest {
  @Test
  void reportsInheritedConditionsWithoutEvaluatingThemAtStartup() throws Exception {
    var conditions = new AtomicInteger();
    var facts = new ArrayList<RouteInfo>();

    try (var app = new Shoostr()) {
      app.routes(
          routes ->
              routes.path(
                  "/api",
                  group -> {
                    group.get("/public", (ignored, ignored2) -> {});
                    group.when(
                        () -> conditions.incrementAndGet() > 0,
                        conditional -> conditional.get("/private", (ignored3, ignored4) -> {}));
                  },
                  e -> {
                    Consumer<RouteInfo> collect = facts::add;
                    e.onRoute(collect);
                  }));

      try (var test = TestServer.start(app)) {
        assertEquals(
            List.of(
                new RouteInfo(HttpMethods.GET, "/api/public", false),
                new RouteInfo(HttpMethods.GET, "/api/private", true)),
            facts);
        assertEquals(0, conditions.get());
        assertEquals(200, test.send(request -> request.path("/api/private")).statusCode());
        assertEquals(1, conditions.get());
      }
    }
  }
}
