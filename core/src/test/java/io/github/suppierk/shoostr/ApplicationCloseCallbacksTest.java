package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationCloseCallbacksTest {
  private static final String BEFORE_CLOSE = "beforeClose";
  private static final String AFTER_CLOSE = "afterClose";

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void returnsTheSameApplicationWhenRegisteringCallbacks(String phase) throws Exception {
    try (var app = new Shoostr()) {
      Function<Runnable, Shoostr> register =
          BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      assertSame(app, register.apply(() -> {}));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsNullCallbackAtRegistration(String phase) throws Exception {
    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      assertThrows(NullPointerException.class, () -> register.accept(null));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsCallbackRegistrationAfterStartup(String phase) throws Exception {
    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      app.start();
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      assertThrows(IllegalStateException.class, () -> register.accept(() -> {}));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsCallbackRegistrationAfterClosure(String phase) throws Exception {
    try (var app = new Shoostr()) {
      app.close();
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      assertThrows(IllegalStateException.class, () -> register.accept(() -> {}));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesCallbackOnTheClosingThread(String phase) throws Exception {
    var closing = Thread.currentThread();
    var invoked = new AtomicReference<Thread>();

    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      register.accept(() -> invoked.set(Thread.currentThread()));
    }

    assertSame(closing, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesCallbackOnceDespiteRepeatedClose(String phase) throws Exception {
    var invoked = new AtomicInteger();

    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      register.accept(invoked::incrementAndGet);
      app.close();
      app.close();
    }

    assertEquals(1, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesEveryRegistrationWhenTheCallbackInstanceIsShared(String phase) throws Exception {
    var invoked = new AtomicInteger();
    Runnable callback = invoked::incrementAndGet;

    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      register.accept(callback);
      register.accept(callback);
    }

    assertEquals(2, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void ignoresRecursiveCloseFromACallback(String phase) throws Exception {
    var invoked = new AtomicInteger();

    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      register.accept(
          () -> {
            invoked.incrementAndGet();
            assertDoesNotThrow(app::close);
          });
    }

    assertEquals(1, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void continuesOtherCallbacksAfterOneFails(String phase) throws Exception {
    var disposed = new AtomicBoolean();
    var rejected = new IllegalStateException("Cleanup failed");
    Runnable throwing =
        () -> {
          throw rejected;
        };
    Runnable succeeding = () -> disposed.set(true);

    try (var app = new Shoostr()) {
      Consumer<Runnable> register = BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
      register.accept(BEFORE_CLOSE.equals(phase) ? throwing : succeeding);
      register.accept(BEFORE_CLOSE.equals(phase) ? succeeding : throwing);

      var failure = assertThrows(IOException.class, app::close);
      assertSame(rejected, failure.getCause());
      assertTrue(disposed.get());
    }
  }

  @Test
  void collectsFailuresFromBothCallbackPhases() throws Exception {
    var before = new IllegalStateException("Stop failed");
    var after = new IllegalArgumentException("Dispose failed");

    try (var app = new Shoostr()) {
      app.beforeClose(
          () -> {
            throw before;
          });
      app.afterClose(
          () -> {
            throw after;
          });

      var failure = assertThrows(IOException.class, app::close);
      assertSame(before, failure.getCause());
      assertArrayEquals(new Throwable[] {after}, failure.getSuppressed());
    }
  }

  @Test
  void runsCallbacksWhenExtensionInstallationFails() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalStateException("Install failed");

    try (var app = new Shoostr()) {
      app.beforeClose(() -> invoked.add("stop"));
      app.afterClose(() -> invoked.add("dispose"));
      var extension = new FailingInstallation(rejected);
      assertSame(
          rejected, assertThrows(IllegalStateException.class, () -> app.extensions(extension)));
    }

    assertEquals(List.of("stop", "dispose"), invoked);
  }

  @Test
  void disposesApplicationResourcesAfterReentrantOwnershipClaimCleanup() throws Exception {
    var invoked = new ArrayList<String>();

    try (var app = new Shoostr()) {
      app.afterClose(() -> invoked.add("registry"));
      app.afterClose(() -> invoked.add("client"));
      var extension =
          new ClaimingExtension(
              () -> assertDoesNotThrow(app::close),
              () -> invoked.add("beforeStop"),
              () -> invoked.add("extension"));

      assertThrows(IllegalStateException.class, () -> app.extensions(extension));
    }

    assertEquals(List.of("beforeStop", "extension", "client", "registry"), invoked);
  }

  @Test
  void preservesClaimCleanupFailuresBeforeDisposingApplicationResources() throws Exception {
    var invoked = new ArrayList<String>();
    var stopping = new IllegalArgumentException("Signal failed");
    var cleanup = new IllegalStateException("Extension cleanup failed");

    try (var app = new Shoostr()) {
      app.afterClose(() -> invoked.add("registry"));
      var extension =
          new ClaimingExtension(
              () -> assertDoesNotThrow(app::close),
              () -> {
                invoked.add("beforeStop");
                throw stopping;
              },
              () -> {
                invoked.add("extension");
                throw cleanup;
              });

      var failure = assertThrows(IllegalStateException.class, () -> app.extensions(extension));
      assertArrayEquals(new Throwable[] {stopping, cleanup}, failure.getSuppressed());
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  @Test
  void keepsRejectedClaimCallerOwnedAfterReentrantClosure() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalArgumentException("Claim failed");

    try (var app = new Shoostr()) {
      app.afterClose(() -> invoked.add("registry"));
      app.extensions(
          new ClaimingExtension(
              () -> {}, () -> invoked.add("beforeStop"), () -> invoked.add("extension")));
      var extension =
          new ClaimingExtension(
              () -> {
                assertDoesNotThrow(app::close);
                throw rejected;
              },
              () -> invoked.add("rejected beforeStop"),
              () -> invoked.add("rejected extension"));

      assertSame(
          rejected, assertThrows(IllegalArgumentException.class, () -> app.extensions(extension)));
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  @Test
  void preservesInstallationFailureWhenFinalCallbackAlsoFails() throws Exception {
    var invoked = new AtomicInteger();
    var rejected = new IllegalArgumentException("Install failed");
    var cleanup = new IllegalStateException("Application cleanup failed");

    try (var app = new Shoostr()) {
      app.afterClose(invoked::incrementAndGet);
      app.afterClose(
          () -> {
            throw cleanup;
          });

      var extension = new FailingInstallation(rejected);
      var failure = assertThrows(IllegalArgumentException.class, () -> app.extensions(extension));
      assertSame(rejected, failure);
      assertEquals(1, failure.getSuppressed().length);
      var callbackFailure = assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
      assertSame(cleanup, callbackFailure.getCause());
    }

    assertEquals(1, invoked.get());
  }

  @Test
  void disposesApplicationResourcesAfterReentrantInstallationCleanup() throws Exception {
    var invoked = new ArrayList<String>();

    try (var app = new Shoostr()) {
      app.afterClose(() -> invoked.add("registry"));
      var extension = new ClosingInstallation(app, invoked);
      assertThrows(IllegalStateException.class, () -> app.extensions(extension));
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  @Test
  void runsCallbacksWhenListenerStartupFails() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalStateException("Start failed");

    try (var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))) {
      app.beforeClose(() -> invoked.add("stop"));
      app.afterClose(() -> invoked.add("dispose"));
      app.modifyServer(
          ignored -> {
            throw rejected;
          });
      assertSame(rejected, assertThrows(IllegalStateException.class, app::start));
    }

    assertEquals(List.of("stop", "dispose"), invoked);
  }

  @Test
  void leavesRegisteredCleanupPendingAfterRejectedCompilation() throws Exception {
    var invoked = new AtomicInteger();

    try (var app = new Shoostr()) {
      app.afterClose(invoked::incrementAndGet);
      app.routes(ignored2 -> assertThrows(IllegalStateException.class, app::start));
      assertEquals(0, invoked.get());
    }

    assertEquals(1, invoked.get());
  }

  @Test
  void rejectsRouteRegistrationThroughRetainedScopesWhenCloseBegins() throws Exception {
    var verified = new AtomicBoolean();

    try (var app = new Shoostr()) {
      var root = app.routes();
      var child = new AtomicReference<Routes>();
      root.path("/child", child::set);
      var nested = child.get();
      app.beforeClose(
          () -> {
            assertAll(
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            app.routes(routes -> routes.get("/late", (ignored3, ignored4) -> {}))),
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () -> root.get("/late", (ignored5, ignored6) -> {})),
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () -> nested.get("/late", (ignored7, ignored8) -> {})));
            verified.set(true);
          });
    }

    assertTrue(verified.get());
  }

  @Test
  void releasesApplicationResourcesAfterShutdownCallbackFails() throws Exception {
    var disposed = new AtomicBoolean();
    var rejected = new IllegalStateException("Application stop failed");

    try (var app = new Shoostr()) {
      app.afterClose(() -> disposed.set(true));
      app.beforeClose(
          () -> {
            throw rejected;
          });

      var failure = assertThrows(IOException.class, app::close);
      assertSame(rejected, failure.getCause());
      assertTrue(disposed.get());
    }
  }

  @Test
  void signalsUnstartedApplicationWorkInRegistrationOrder() throws Exception {
    var signalled = new ArrayList<String>();

    try (var app = new Shoostr()) {
      app.beforeClose(() -> signalled.add("producer"));
      app.beforeClose(() -> signalled.add("client"));
    }

    assertEquals(List.of("producer", "client"), signalled);
  }

  @Test
  void closesUnstartedApplicationResourcesInReverseRegistrationOrder() throws Exception {
    var closed = new ArrayList<String>();

    try (var app = new Shoostr()) {
      app.afterClose(() -> closed.add("registry"));
      app.afterClose(() -> closed.add("client"));
    }

    assertEquals(List.of("client", "registry"), closed);
  }

  private record ClaimingExtension(Runnable claim, Runnable stopping, Runnable cleanup)
      implements Extension<Void> {
    private ClaimingExtension {
      Objects.requireNonNull(claim);
      Objects.requireNonNull(stopping);
      Objects.requireNonNull(cleanup);
    }

    @Override
    public void claimOwnership() {
      claim.run();
    }

    @Override
    public void beforeStop() {
      stopping.run();
    }

    @Override
    public void close() {
      cleanup.run();
    }
  }

  private record FailingInstallation(RuntimeException failure) implements Extension<Void> {
    private FailingInstallation {
      Objects.requireNonNull(failure);
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      throw failure;
    }
  }

  private record ClosingInstallation(Shoostr app, List<String> invoked) implements Extension<Void> {
    private ClosingInstallation {
      Objects.requireNonNull(app);
      Objects.requireNonNull(invoked);
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      assertDoesNotThrow(app::close);
    }

    @Override
    public void beforeStop() {
      invoked.add("beforeStop");
    }

    @Override
    public void close() {
      invoked.add("extension");
    }
  }
}
