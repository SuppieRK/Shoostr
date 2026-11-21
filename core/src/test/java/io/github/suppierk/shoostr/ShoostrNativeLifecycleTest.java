package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.HouseKeeper;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShoostrNativeLifecycleTest {
  @Test
  void sessionCustomizationCannotReplaceTheOwnedApplicationChild() throws Exception {
    var application = new AtomicReference<Handler.Wrapper>();
    var nativeStarts = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> {
        server.addEventListener(startCounter(nativeStarts));
        application.set((Handler.Wrapper) server.getDescendant(ContextHandler.class).getHandler());
      });
      app.sessions(sessions -> application.get().setHandler(new DefaultHandler()));

      assertThrows(IllegalStateException.class, app::start);
      assertEquals(0, nativeStarts.get());
    }
  }

  @Test
  void sessionCustomizationCannotStartTheOwnedSessionHandlerPrematurely() throws Exception {
    var captured = new AtomicReference<SessionHandler>();
    var manager = new AtomicReference<DefaultSessionIdManager>();
    var nativeStarts = new AtomicInteger();
    var app = new Shoostr();

    try {
      try (Closeable appCleanup = app::close) {
        app.modifyServer(server -> {
          server.addEventListener(startCounter(nativeStarts));
          var ids = new DefaultSessionIdManager(server);
          ids.setSessionHouseKeeper(assertDoesNotThrow(this::inactiveHouseKeeper));
          manager.set(ids);
          server.addBean(ids, true);
        });
        app.sessions(sessions -> {
          captured.set(sessions);
          assertDoesNotThrow(sessions::start);
          assertTrue(sessions.isStarted());
        });

        assertThrows(IllegalStateException.class, app::start);
        assertEquals(0, nativeStarts.get());
      }
    } finally {
      try {
        var sessions = captured.get();
        if (sessions != null) {
          sessions.stop();
        }
      } finally {
        var ids = manager.get();
        if (ids != null) {
          ids.stop();
        }
      }
    }
  }

  private HouseKeeper inactiveHouseKeeper() throws Exception {
    var houseKeeper = new HouseKeeper();
    // Premature-start validation must not depend on the unstarted server's scheduler.
    houseKeeper.setIntervalSec(0);
    return houseKeeper;
  }

  @Test
  void stoppingTheSessionHandlerDuringStartupPreventsTheConnectorFromStarting() throws Exception {
    var stopped = new AtomicInteger();
    var listening = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> observeConnectorStartup(server, listening));
      app.sessions(sessions -> sessions.addEventListener(stopSessionAfterStartup(stopped)));

      assertThrows(IllegalStateException.class, app::start);
      assertEquals(1, stopped.get());
      assertEquals(0, listening.get());
    }
  }

  private void observeConnectorStartup(Server server, AtomicInteger listening) {
    for (var connector : server.getConnectors()) {
      ((ServerConnector) connector).setPort(0);
      connector.addEventListener(connectorStarted(listening));
    }
  }

  private LifeCycle.Listener connectorStarted(AtomicInteger listening) {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarted(LifeCycle event) {
        listening.incrementAndGet();
      }
    };
  }

  private LifeCycle.Listener stopSessionAfterStartup(AtomicInteger stopped) {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarted(LifeCycle event) {
        assertDoesNotThrow(() -> {
          event.stop();
        });
        stopped.incrementAndGet();
      }
    };
  }

  @Test
  void replacingTheApplicationChildIsRejectedBeforeSessionCustomization() throws Exception {
    var configured = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.sessions(ignored -> configured.incrementAndGet());
      app.modifyServer(server -> {
        var context = server.getDescendant(ContextHandler.class);
        var application = (Handler.Wrapper) context.getHandler();
        application.setHandler(new DefaultHandler());
      });

      assertThrows(IllegalStateException.class, app::start);
      assertEquals(0, configured.get());
    }
  }

  @Test
  void replacingTheSessionContinuationIsRejectedBeforeNativeStartup() throws Exception {
    var nativeStarts = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> server.addEventListener(startCounter(nativeStarts)));
      app.sessions(sessions -> sessions.setHandler(new DefaultHandler()));

      assertThrows(IllegalStateException.class, app::start);
      assertEquals(0, nativeStarts.get());
    }
  }

  private LifeCycle.Listener startCounter(AtomicInteger starts) {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarting(LifeCycle event) {
        starts.incrementAndGet();
      }
    };
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closingInsideTransportConfigurationPreventsLaterConfiguration(boolean tls) throws Exception {
    var app = new Shoostr();
    var laterHttp = new AtomicInteger();
    var laterNative = new AtomicInteger();

    try (Closeable appCleanup = app::close) {
      if (tls) {
        app.tls(ignored -> closeApplication(app));
      } else {
        app.modifyHttpConfiguration(ignored -> closeApplication(app));
      }

      app.modifyHttpConfiguration(ignored -> laterHttp.incrementAndGet());
      app.modifyServer(ignored -> laterNative.incrementAndGet());

      assertThrows(IllegalStateException.class, app::start);
      assertEquals(tls ? 1 : 0, laterHttp.get());
      assertEquals(0, laterNative.get());
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  private void closeApplication(Shoostr app) {
    try {
      app.close();
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void portIsUnavailableAfterTheNativeServerHasStopped() throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> configureServer(server, nativeServer));
      app.start();
      assertTrue(app.port() > 0);
      nativeServer.get().stop();

      assertThrows(IllegalStateException.class, app::port);
    }
  }

  @Test
  void restoresTheInterruptWhenNativeStopThrowsInterruptedException() throws Exception {
    var interruption = new InterruptedException("native stop interrupted");
    var stops = new AtomicInteger();
    var nativeServer = new AtomicReference<Server>();
    var app = new Shoostr();

    try {
      try (Closeable appCleanup = app::close) {
        app.modifyServer(server -> {
          configureServer(server, nativeServer);
          server.addBean(interruptedStop(interruption, stops), true);
        });
        app.start();
        assertFalse(Thread.currentThread().isInterrupted());

        var failure = assertThrows(IOException.class, app::close);

        assertSame(interruption, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, stops.get());
      }
    } finally {
      Thread.interrupted();
      var server = nativeServer.get();
      if (server != null) {
        try {
          server.stop();
        } finally {
          server.destroy();
        }
      }
    }
  }

  private void configureServer(Server server, AtomicReference<Server> captured) {
    captured.set(server);
    ((ServerConnector) server.getConnectors()[0]).setPort(0);
  }

  private AbstractLifeCycle interruptedStop(
      InterruptedException interruption, AtomicInteger stops) {
    return new AbstractLifeCycle() {
      @Override
      protected void doStop() throws Exception {
        if (stops.incrementAndGet() == 1) {
          throw interruption;
        }
      }
    };
  }

  @Test
  void sessionsHaveBoundedDefaultIdleLifetimeBeforeUserConfiguration() throws Exception {
    var configured = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));
      app.sessions(sessions -> {
        configured.incrementAndGet();
        assertEquals(1800, sessions.getMaxInactiveInterval());
      });
      app.start();
      assertEquals(1, configured.get());
    }
  }
}
