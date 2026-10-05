package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class PortSelectionTest {
  @Test
  void servesTheSuppliedApplicationOnTheExactRequestedPort() throws Exception {
    int port;

    try (var socket = bind(0)) {
      port = socket.getLocalPort();
    }

    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("fixed"));

    try (var test = TestServer.startOnPort(app, port)) {
      assertEquals(port, test.baseUri().getPort());
      assertEquals("127.0.0.1", test.baseUri().getHost());
      assertEquals(
          "fixed",
          test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  void skipsAnOccupiedRangeCandidateAndBindsTheNextCandidate() throws Exception {
    var ports = adjacentPorts();

    try (var occupied = ports.get(0);
        var available = ports.get(1)) {
      int minimum = occupied.getLocalPort();
      int maximum = available.getLocalPort();
      available.close();
      var app = new Shoostr();
      app.routes().get("/value", (_, response) -> response.text("range"));

      try (var test = TestServer.startOnPortRange(app, minimum, maximum)) {
        assertEquals(maximum, test.baseUri().getPort());
        assertEquals(
            "range",
            test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void choosesTheLowestPortWhenBothRangeCandidatesAreAvailable() throws Exception {
    var ports = adjacentPorts();

    try (var first = ports.get(0);
        var second = ports.get(1)) {
      int minimum = first.getLocalPort();
      int maximum = second.getLocalPort();
      first.close();
      second.close();

      try (var test = TestServer.startOnPortRange(new Shoostr(), minimum, maximum)) {
        assertEquals(minimum, test.baseUri().getPort());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void appliesClientCustomizationForBothNamedPortFactories(boolean range) throws Exception {
    int port;

    try (var socket = bind(0)) {
      port = socket.getLocalPort();
    }

    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("custom client"));

    try (var test =
        range
            ? TestServer.startOnPortRange(
                app, port, port, client -> client.connectTimeout(Duration.ofSeconds(7)))
            : TestServer.startOnPort(
                app, port, client -> client.connectTimeout(Duration.ofSeconds(7)))) {
      assertEquals(Duration.ofSeconds(7), test.httpClient().connectTimeout().orElseThrow());
      assertEquals(HttpClient.Redirect.NEVER, test.httpClient().followRedirects());
      assertEquals(200, test.send(request -> request.path("/value")).statusCode());
    }
  }

  @Test
  void failsAnOccupiedFixedPortWithoutSelectingAnotherPort() throws Exception {
    try (var occupied = bind(0);
        var app = new Shoostr()) {
      var failure =
          assertThrows(
              IOException.class, () -> TestServer.startOnPort(app, occupied.getLocalPort()));
      assertInstanceOf(BindException.class, failure.getCause());
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void failsAnExhaustedRangeAndPreservesTheFirstBindFailure() throws Exception {
    var ports = adjacentPorts();

    try (var first = ports.get(0);
        var second = ports.get(1);
        var app = new Shoostr()) {
      var failure =
          assertThrows(
              IOException.class,
              () -> TestServer.startOnPortRange(app, first.getLocalPort(), second.getLocalPort()));
      assertInstanceOf(BindException.class, failure.getCause());
      assertTrue(Objects.requireNonNull(failure.getMessage()).contains(":" + first.getLocalPort()));
      assertThrows(IllegalStateException.class, app::port);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void keepsCleanupFailuresSuppressedOnTheCheckedBindFailure() throws Exception {
    var expected = new IOException("forced extension cleanup failure");
    var extension =
        new Extension<>() {
          @Override
          public void close() throws IOException {
            throw expected;
          }
        };

    try (var occupied = bind(0);
        var app = new Shoostr().extensions(extension)) {
      var failure =
          assertThrows(
              IOException.class, () -> TestServer.startOnPort(app, occupied.getLocalPort()));
      assertInstanceOf(BindException.class, failure.getCause());
      assertEquals(1, failure.getSuppressed().length);
      assertSame(expected, failure.getSuppressed()[0].getCause());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void releasesTheReservationWhenEitherNamedPortFixtureCloses(boolean range) throws Exception {
    int port;

    try (var socket = bind(0)) {
      port = socket.getLocalPort();
    }

    try (var test =
        range
            ? TestServer.startOnPortRange(new Shoostr(), port, port)
            : TestServer.startOnPort(new Shoostr(), port)) {
      assertEquals(port, test.baseUri().getPort());
      assertThrows(
          BindException.class,
          () -> {
            try (var _ = bind(port)) {}
          });
    }

    try (var rebound = bind(port)) {
      assertEquals(port, rebound.getLocalPort());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 65536})
  void rejectsInvalidFixedPortsBeforeTakingOwnership(int port) throws Exception {
    try (var app = new Shoostr()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> TestServer.startOnPort(app, port, _ -> fail("must reject before client setup")));
      app.routes().get("/value", (_, response) -> response.text("not closed"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"-1,10", "0,10", "20,19", "1,65536", "65536,65536"})
  void rejectsInvalidRangesBeforeTakingOwnership(int minimum, int maximum) throws Exception {
    try (var app = new Shoostr()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              TestServer.startOnPortRange(
                  app, minimum, maximum, _ -> fail("must reject before client setup")));
      app.routes().get("/value", (_, response) -> response.text("not closed"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      }
    }
  }

  @Test
  void releasesTheReservedPortWhenJettyFailsBeforeStarting() throws Exception {
    int port;

    try (var socket = bind(0)) {
      port = socket.getLocalPort();
    }

    var expected = new IllegalStateException("forced startup failure");

    try (var app = new Shoostr()) {
      app.modifyServer(
          server ->
              server.addEventListener(
                  new LifeCycle.Listener() {
                    @Override
                    public void lifeCycleStarting(LifeCycle event) {
                      var listener = (ServerConnector) server.getConnectors()[0];
                      assertEquals(port, listener.getLocalPort());
                      assertThrows(
                          BindException.class,
                          () -> {
                            try (var _ = bind(port)) {}
                          });
                      throw expected;
                    }
                  }));
      assertSame(
          expected,
          assertThrows(
              IllegalStateException.class, () -> TestServer.startOnPortRange(app, port, port)));
      assertThrows(IllegalStateException.class, app::start);

      try (var rebound = bind(port)) {
        assertEquals(port, rebound.getLocalPort());
      }
    }
  }

  private static List<ServerSocket> adjacentPorts() throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      var first = bind(0);
      if (first.getLocalPort() == 65535) {
        first.close();
        continue;
      }

      try {
        return List.of(first, bind(first.getLocalPort() + 1));
      } catch (IOException failure) {
        try (first) {
          if (!(failure instanceof BindException)) {
            throw failure;
          }
        }
      }
    }

    throw new IllegalStateException("Could not reserve two adjacent loopback ports for the test");
  }

  private static ServerSocket bind(int port) throws IOException {
    var socket = new ServerSocket();

    try {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port));
      return socket;
    } catch (IOException failure) {
      try {
        socket.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }
}
