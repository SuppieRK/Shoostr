package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

class ExtensionOwnershipTest {
  private static AuthenticationExtension owningAuthenticator(OwnedCapability capability) {
    return new AuthenticationExtension() {
      @Override
      public void claimOwnership() {
        capability.claimOwnership();
      }

      @Override
      public void handle(Request request, Response response) {
        // This ownership fixture deliberately contributes no request behavior.
      }

      @Override
      public void close() {
        capability.close();
      }
    };
  }

  private static boolean installAfterRelease(
      Shoostr app, OwnedCapability capability, CountDownLatch ready, CountDownLatch release)
      throws InterruptedException {
    ready.countDown();

    assertTrue(release.await(5, TimeUnit.SECONDS));

    try {
      app.withExtension(capability);

      return true;
    } catch (IllegalStateException ignoredFailure) {
      return false;
    }
  }

  private static ApplicationExtension<Object> loggedCapability(
      String name, List<String> events, boolean failsSetup) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void claimOwnership() {
        events.add("claim-" + name);
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        events.add("install-" + name);
        if (failsSetup) {
          throw new IllegalArgumentException("setup failed");
        }
      }

      @Override
      public void close() {
        events.add("close-" + name);
      }
    };
  }

  @Test
  void rejectedReuseLeavesTheOwningApplicationOperational() throws Exception {
    var capability = new OwnedCapability();

    {
      var owner = new Shoostr();

      try (Closeable ownerCleanup = owner::close) {
        owner
            .withExtension(capability)
            .routes()
            .get("/", (ignored, response) -> response.text("ok"));

        try (var test = TestServer.start(owner)) {
          var rejected = new Shoostr();

          try (Closeable rejectedCleanup = rejected::close) {
            assertThrows(IllegalStateException.class, () -> rejected.withExtension(capability));
            assertEquals(200, test.send(request -> request.path("/")).statusCode());
            assertEquals(0, capability.closes.get());
          }
        }
      }
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void closesOnlyTheClaimedPrefixWhenOwnershipIsRejected() throws Exception {
    var first = new OwnedCapability();
    var foreign = new OwnedCapability();
    var unclaimed = new OwnedCapability();

    {
      var owner = new Shoostr();

      try (Closeable ownerCleanup1 = owner::close) {
        var rejected = new Shoostr();

        try (Closeable rejectedCleanup1 = rejected::close) {
          owner.withExtension(foreign);

          assertThrows(
              IllegalStateException.class,
              () -> rejected.withExtension(first).withExtension(foreign).withExtension(unclaimed));
          assertEquals(1, first.closes.get());
          assertEquals(0, foreign.closes.get());
          assertEquals(0, unclaimed.closes.get());
          assertThrows(IllegalStateException.class, rejected::start);

          {
            var laterOwner = new Shoostr();

            try (Closeable laterOwnerCleanup = laterOwner::close) {
              laterOwner.withExtension(unclaimed);
            }
          }

          assertEquals(1, unclaimed.closes.get());
        }
      }
    }

    assertEquals(1, first.closes.get());
    assertEquals(1, foreign.closes.get());
  }

  @Test
  void failedSetupDoesNotAttemptOwnershipOfLaterProvider() throws Exception {
    var foreign = new OwnedCapability();
    var setups = new AtomicInteger();
    var closes = new AtomicInteger();
    ApplicationExtension<Object> broken = setupFailingProvider(setups, closes);

    {
      var owner = new Shoostr();

      try (Closeable ownerCleanup2 = owner::close) {
        var rejected = new Shoostr();

        try (Closeable rejectedCleanup2 = rejected::close) {
          owner.withExtension(foreign);

          assertThrows(
              IllegalArgumentException.class,
              () -> rejected.withExtension(broken).withExtension(foreign));
          assertEquals(1, setups.get());
          assertEquals(1, closes.get());
          assertEquals(0, foreign.closes.get());
        }
      }
    }

    assertEquals(1, foreign.closes.get());
  }

  private ApplicationExtension<Object> setupFailingProvider(
      AtomicInteger setups, AtomicInteger closes) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        setups.incrementAndGet();
        throw new IllegalArgumentException("setup failed");
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    };
  }

  @Test
  void installationFailureClosesClaimedProvidersWithoutClaimingLaterOnes() throws Exception {
    var events = new ArrayList<String>();
    var first = loggedCapability("first", events, false);
    var broken = loggedCapability("broken", events, true);
    var last = loggedCapability("last", events, false);

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        assertThrows(
            IllegalArgumentException.class,
            () -> app.withExtension(first).withExtension(broken).withExtension(last));
      }
    }

    assertEquals(
        List.of(
            "claim-first",
            "install-first",
            "claim-broken",
            "install-broken",
            "close-broken",
            "close-first"),
        events);
  }

  @Test
  void concurrentApplicationsHaveExactlyOneOwnerWithoutClosingItsResource() throws Exception {
    var capability = new OwnedCapability();
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);

    {
      var first =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable firstCleanup = first::close) {
        var second =
            new Shoostr()
                .modifyServer(
                    nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

        try (Closeable secondCleanup = second::close;
            var workers = Executors.newVirtualThreadPerTaskExecutor()) {
          var firstAttempt =
              workers.submit(() -> installAfterRelease(first, capability, ready, release));
          var secondAttempt =
              workers.submit(() -> installAfterRelease(second, capability, ready, release));

          try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
          } finally {
            release.countDown();
          }

          var firstWon = firstAttempt.get(5, TimeUnit.SECONDS);

          assertEquals(!firstWon, secondAttempt.get(5, TimeUnit.SECONDS));

          var owner = firstWon ? first : second;
          owner.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

          try (var test = TestServer.start(owner)) {
            assertEquals(200, test.send(request -> request.path("/")).statusCode());
            assertEquals(0, capability.closes.get());
          }
        }
      }
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void nullAndDuplicateActivationDoNotRepeatOwnershipClaims() throws Exception {
    var capability = new OwnedCapability();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        assertThrows(
            NullPointerException.class, () -> app.withExtension((ApplicationExtension<?>) null));

        app.withExtension(capability);

        assertThrows(IllegalArgumentException.class, () -> app.withExtension(capability));
      }
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void rejectedAuthenticationOwnershipDoesNotCloseTheOtherApplicationsIdentitySource()
      throws Exception {
    var owned = new OwnedCapability();
    var authentication = owningAuthenticator(owned);
    var prefix = new OwnedCapability();
    var prefixAuthentication = owningAuthenticator(prefix);

    {
      var owner = new Shoostr();

      try (Closeable ownerCleanup3 = owner::close) {
        var rejected = new Shoostr();

        try (Closeable rejectedCleanup3 = rejected::close) {
          owner.withExtension(authentication);

          assertThrows(
              IllegalStateException.class,
              () -> rejected.withExtension(prefixAuthentication).withExtension(authentication));
          assertEquals(1, prefix.closes.get());
          assertEquals(0, owned.closes.get());
        }
      }
    }

    assertEquals(1, owned.closes.get());
  }

  @Test
  void cleanupFailureDoesNotReplaceTheOwnershipRejection() throws Exception {
    var rejection = new IllegalStateException("ownership rejected");
    class FailingCleanup implements ApplicationExtension<Object> {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void close() throws IOException {
        throw new IOException("cleanup failed");
      }
    }

    ApplicationExtension<Object> prefix = new FailingCleanup();
    class RejectedClaim implements ApplicationExtension<Object> {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void claimOwnership() {
        throw rejection;
      }
    }

    ApplicationExtension<Object> rejected = new RejectedClaim();

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        var failure =
            assertThrows(
                IllegalStateException.class,
                () -> app.withExtension(prefix).withExtension(rejected));
        assertSame(rejection, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("cleanup failed", failure.getSuppressed()[0].getCause().getMessage());
      }
    }
  }

  @Test
  void anOwnershipErrorClosesOnlyTheSuccessfullyClaimedResources() throws Exception {
    var prefix = new OwnedCapability();
    var unclaimed = new OwnedCapability();
    var rejectedCloses = new AtomicInteger();
    ApplicationExtension<Object> rejected = claimFailingProvider(rejectedCloses);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        assertThrows(
            LinkageError.class,
            () -> app.withExtension(prefix).withExtension(rejected).withExtension(unclaimed));
        assertEquals(1, prefix.closes.get());
        assertEquals(0, rejectedCloses.get());
        assertEquals(0, unclaimed.closes.get());
        assertThrows(IllegalStateException.class, app::start);
      }
    }

    assertEquals(1, prefix.closes.get());
  }

  private ApplicationExtension<Object> claimFailingProvider(AtomicInteger rejectedCloses) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void claimOwnership() {
        throw new LinkageError("ownership failed");
      }

      @Override
      public void close() {
        rejectedCloses.incrementAndGet();
      }
    };
  }

  @Test
  void rejectsReentrantExtensionInstallationDuringOwnershipClaims() throws Exception {
    var nested = new OwnedCapability();

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        class ReentrantInstallation implements ApplicationExtension<Object> {
          @Override
          public Object configure(Lifecycle lifecycle, Object inherited) {
            return this;
          }

          @Override
          public void claimOwnership() {
            assertThrows(IllegalStateException.class, () -> app.withExtension(nested));
          }
        }

        ApplicationExtension<Object> first = new ReentrantInstallation();
        app.withExtension(first);
        app.withExtension(nested)
            .routes()
            .get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/")).statusCode());
        }
      }
    }

    assertEquals(1, nested.closes.get());
  }

  @Test
  void rejectsReentrantAuthenticationInstallationDuringOwnershipClaims() throws Exception {
    var owned = new OwnedCapability();
    var nested = owningAuthenticator(owned);

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        class ReentrantAuthentication implements ApplicationExtension<Object> {
          @Override
          public Object configure(Lifecycle lifecycle, Object inherited) {
            return this;
          }

          @Override
          public void claimOwnership() {
            assertThrows(IllegalStateException.class, () -> app.withExtension(nested));
          }
        }

        ApplicationExtension<Object> first = new ReentrantAuthentication();
        app.withExtension(first);
        app.withExtension(nested)
            .routes()
            .get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/")).statusCode());
        }
      }
    }

    assertEquals(1, owned.closes.get());
  }

  @Test
  void rejectsReentrantStartupDuringOwnershipClaims() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        class ReentrantStartup implements ApplicationExtension<Object> {
          @Override
          public Object configure(Lifecycle lifecycle, Object inherited) {
            return this;
          }

          @Override
          public void claimOwnership() {
            assertThrows(IllegalStateException.class, app::start);
          }
        }

        ApplicationExtension<Object> first = new ReentrantStartup();
        app.withExtension(first)
            .routes()
            .get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/")).statusCode());
        }
      }
    }
  }

  @Test
  void closesSuccessfullyClaimedResourceWhenTheClaimClosesTheApplication() throws Exception {
    var closes = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        ApplicationExtension<Object> capability = closingClaimProvider(app, closes);

        assertThrows(IllegalStateException.class, () -> app.withExtension(capability));
        assertEquals(1, closes.get());
      }
    }

    assertEquals(1, closes.get());
  }

  private ApplicationExtension<Object> closingClaimProvider(Shoostr app, AtomicInteger closes) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void claimOwnership() {
        assertDoesNotThrow(app::close);
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    };
  }

  private static final class OwnedCapability implements ApplicationExtension<Object> {

    private final AtomicBoolean claimed;
    private final AtomicInteger closes;

    private OwnedCapability() {
      this.claimed = new AtomicBoolean();
      this.closes = new AtomicInteger();
    }

    @Override
    public Object configure(Lifecycle lifecycle, Object inherited) {
      return this;
    }

    @Override
    public void claimOwnership() {
      if (!claimed.compareAndSet(false, true)) {
        throw new IllegalStateException("This capability already has an owner");
      }
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      callbacks.beforeRouteHandler(
          (ignoredRequest, ignoredResponse) -> {
            if (closes.get() != 0) {
              throw new IllegalStateException("The owned resource is closed");
            }
          });
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
