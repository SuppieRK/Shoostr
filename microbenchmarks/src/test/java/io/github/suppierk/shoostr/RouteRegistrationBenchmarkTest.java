package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteRegistrationBenchmarkTest {
  @ParameterizedTest
  @ValueSource(strings = {"literal", "constrained"})
  void createsExpectedEndpointsAndClosesTheFixtureExecutor(String pattern) {
    var fixture = new RouteRegistrationBenchmark();
    fixture.pattern = pattern;
    fixture.setup();

    try {
      var http = assertInstanceOf(RadixRoutes.Endpoint.class, fixture.http());
      final var websocket = assertInstanceOf(RadixRoutes.Endpoint.class, fixture.websocket());
      final var behavior = assertInstanceOf(RadixRoutes.Endpoint.class, fixture.withBehavior());
      final var executor = assertInstanceOf(RadixRoutes.Endpoint.class, fixture.withExecutor());

      assertEquals(HttpMethods.GET, http.method());
      assertEquals(fixture.path, http.routePattern());
      assertEquals(
          "literal".equals(pattern) ? Map.of() : Map.of("id", 2, "tail", -4), http.parameters());
      assertSame(RouteRegistrationBenchmark.HANDLER, http.handler());
      assertNull(http.websocketFactory());
      assertEquals(http.pattern(), websocket.pattern());
      assertEquals(http.parameters(), websocket.parameters());
      assertSame(RouteRegistrationBenchmark.FACTORY, websocket.websocketFactory());
      assertSame(fixture.behavior, behavior.behavior());
      assertEquals(http.pattern(), behavior.pattern());
      assertEquals(http.parameters(), behavior.parameters());
      assertSame(fixture.executor, executor.executor());
      assertEquals(http.pattern(), executor.pattern());
      assertEquals(http.parameters(), executor.parameters());
    } finally {
      fixture.tearDown();
    }

    assertTrue(fixture.executor.isTerminated());
  }
}
