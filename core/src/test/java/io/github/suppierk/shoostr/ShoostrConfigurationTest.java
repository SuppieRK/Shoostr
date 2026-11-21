package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Closeable;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShoostrConfigurationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defaultConfigurationIsFluentAndRejectsRepeatedConfiguration(boolean sessions)
      throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      assertSame(app, configure(app, sessions));
      assertThrows(IllegalStateException.class, () -> configure(app, sessions));
      if (!sessions) {
        assertThrows(IllegalStateException.class, () -> app.trustedProxies(null));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defaultConfigurationIsRejectedAfterStartup(boolean sessions) throws Exception {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);
    var app = new Shoostr(options);

    try (Closeable appCleanup = app::close) {
      app.start();

      assertThrows(IllegalStateException.class, () -> configure(app, sessions));
      if (!sessions) {
        assertThrows(IllegalStateException.class, () -> app.trustedProxies(null));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defaultConfigurationIsRejectedAfterCloseBeforeStartup(boolean sessions) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.close();

      assertThrows(IllegalStateException.class, () -> configure(app, sessions));
      if (!sessions) {
        assertThrows(IllegalStateException.class, () -> app.trustedProxies(null));
      }
    }
  }

  @Test
  void nullProxyPredicateDoesNotConfigureProxyTrust() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      assertThrows(NullPointerException.class, () -> app.trustedProxies(null));
      assertSame(app, app.trustedProxies(InetAddress::isLoopbackAddress));
    }
  }

  @Test
  void returnsTheApplicationFromTlsHttpAndStatusConfiguration() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      assertSame(app, app.tls(ignored -> {}));
      assertThrows(IllegalStateException.class, () -> app.tls(ignored -> {}));
      assertSame(app, app.modifyHttpConfiguration(ignored -> {}));
      assertSame(app, app.status(404, (ignoredRequest, ignoredResponse) -> {}));
      assertThrows(
          IllegalArgumentException.class,
          () -> app.status(404, (ignoredRequest, ignoredResponse) -> {}));
      app.close();
      assertThrows(
          IllegalStateException.class,
          () -> app.status(405, (ignoredRequest, ignoredResponse) -> {}));
    }
  }

  private Shoostr configure(Shoostr app, boolean sessions) {
    return sessions ? app.sessions() : app.trustedProxies(InetAddress::isLoopbackAddress);
  }
}
