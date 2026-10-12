package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExtensionOwnershipTest {
  @Test
  void rejectedReuseLeavesTheOwningApplicationOperational() throws Exception {
    var capability = new OwnedCapability();

    try (var owner = new Shoostr(Options.defaults().withPort(0))) {
      owner.extensions(capability).routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(owner);
          var rejected = new Shoostr()) {
        assertThrows(IllegalStateException.class, () -> rejected.extensions(capability));
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
        assertEquals(0, capability.closes.get());
      }
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void closesOnlyTheClaimedPrefixWhenOwnershipIsRejected() throws Exception {
    var first = new OwnedCapability();
    var foreign = new OwnedCapability();
    var unclaimed = new OwnedCapability();

    try (var owner = new Shoostr();
        var rejected = new Shoostr()) {
      owner.extensions(foreign);
      assertThrows(
          IllegalStateException.class, () -> rejected.extensions(first, foreign, unclaimed));
      assertEquals(1, first.closes.get());
      assertEquals(0, foreign.closes.get());
      assertEquals(0, unclaimed.closes.get());
      assertThrows(IllegalStateException.class, rejected::start);

      try (var laterOwner = new Shoostr()) {
        laterOwner.extensions(unclaimed);
      }

      assertEquals(1, unclaimed.closes.get());
    }

    assertEquals(1, first.closes.get());
    assertEquals(1, foreign.closes.get());
  }

  @Test
  void rejectsAForeignOwnerBeforeAnEarlierProviderCanFailSetup() throws Exception {
    var foreign = new OwnedCapability();
    var setups = new AtomicInteger();
    var closes = new AtomicInteger();
    Extension<Void> broken =
        new Extension<>() {
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

    try (var owner = new Shoostr();
        var rejected = new Shoostr()) {
      owner.extensions(foreign);
      assertThrows(IllegalStateException.class, () -> rejected.extensions(broken, foreign));
      assertEquals(0, setups.get());
      assertEquals(1, closes.get());
      assertEquals(0, foreign.closes.get());
    }

    assertEquals(1, foreign.closes.get());
  }

  @Test
  void installationFailureClosesClaimedProvidersWhoseSetupWasNeverReached() throws Exception {
    var events = new ArrayList<String>();
    var first = loggedCapability("first", events, false);
    var broken = loggedCapability("broken", events, true);
    var last = loggedCapability("last", events, false);

    try (var app = new Shoostr()) {
      assertThrows(IllegalArgumentException.class, () -> app.extensions(first, broken, last));
    }

    assertEquals(
        List.of(
            "claim-first",
            "claim-broken",
            "claim-last",
            "install-first",
            "install-broken",
            "close-last",
            "close-broken",
            "close-first"),
        events);
  }

  @Test
  void concurrentApplicationsHaveExactlyOneOwnerWithoutClosingItsResource() throws Exception {
    var capability = new OwnedCapability();
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);

    try (var first = new Shoostr(Options.defaults().withPort(0));
        var second = new Shoostr(Options.defaults().withPort(0));
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
      owner.routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(owner)) {
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
        assertEquals(0, capability.closes.get());
      }
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void structuralRejectionDoesNotConsumeOwnershipClaims() throws Exception {
    var capability = new OwnedCapability();

    try (var app = new Shoostr()) {
      assertThrows(IllegalArgumentException.class, () -> app.extensions(capability, capability));
      assertThrows(
          NullPointerException.class, () -> app.extensions(capability, (Extension<?>) null));
      app.extensions(capability);
    }

    assertEquals(1, capability.closes.get());
  }

  @Test
  void rejectedAuthenticationOwnershipDoesNotCloseTheOtherApplicationsIdentitySource()
      throws Exception {
    var owned = new OwnedCapability();
    var authentication = owningAuthenticator(owned);
    var prefix = new OwnedCapability();

    try (var owner = new Shoostr();
        var rejected = new Shoostr()) {
      owner.authentication(authentication);
      assertThrows(
          IllegalStateException.class,
          () -> rejected.authentication(owningAuthenticator(prefix), authentication));
      assertEquals(1, prefix.closes.get());
      assertEquals(0, owned.closes.get());
    }

    assertEquals(1, owned.closes.get());
  }

  @Test
  void cleanupFailureDoesNotReplaceTheOwnershipRejection() throws Exception {
    var rejection = new IllegalStateException("ownership rejected");
    Extension<Void> prefix =
        new Extension<>() {
          @Override
          public void close() throws IOException {
            throw new IOException("cleanup failed");
          }
        };
    Extension<Void> rejected =
        new Extension<>() {
          @Override
          public void claimOwnership() {
            throw rejection;
          }
        };

    try (var app = new Shoostr()) {
      var failure =
          assertThrows(IllegalStateException.class, () -> app.extensions(prefix, rejected));
      assertSame(rejection, failure);
      assertEquals(1, failure.getSuppressed().length);
      assertEquals("cleanup failed", failure.getSuppressed()[0].getCause().getMessage());
    }
  }

  @Test
  void anOwnershipErrorClosesOnlyTheSuccessfullyClaimedResources() throws Exception {
    var prefix = new OwnedCapability();
    var unclaimed = new OwnedCapability();
    var rejectedCloses = new AtomicInteger();
    Extension<Void> rejected =
        new Extension<>() {
          @Override
          public void claimOwnership() {
            throw new LinkageError("ownership failed");
          }

          @Override
          public void close() {
            rejectedCloses.incrementAndGet();
          }
        };

    try (var app = new Shoostr()) {
      assertThrows(LinkageError.class, () -> app.extensions(prefix, rejected, unclaimed));
      assertEquals(1, prefix.closes.get());
      assertEquals(0, rejectedCloses.get());
      assertEquals(0, unclaimed.closes.get());
      assertThrows(IllegalStateException.class, app::start);
    }

    assertEquals(1, prefix.closes.get());
  }

  @Test
  void currentBatchInstancesRemainUnavailableToRoutesThroughoutOwnershipClaims() throws Exception {
    var first = new OwnedCapability();
    var last = new OwnedCapability();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      Extension<Void> checking =
          new Extension<>() {
            @Override
            public void claimOwnership() {
              assertThrows(
                  IllegalArgumentException.class,
                  () -> routes.get("/first", (_, _) -> {}, e -> e.requireInstalled(first)));
              assertThrows(
                  IllegalArgumentException.class,
                  () -> routes.get("/last", (_, _) -> {}, e -> e.requireInstalled(last)));
            }
          };
      app.extensions(first, checking, last);
      routes.get("/first", (_, response) -> response.text("ok"), e -> e.requireInstalled(first));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/first")).statusCode());
      }
    }
  }

  @Test
  void installationCanUseClaimedProvidersWhoseSetupHasNotRunYet() throws Exception {
    var last = new OwnedCapability();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      Extension<Void> first =
          new Extension<>() {
            @Override
            public void install(ApplicationCallbacks callbacks) {
              app.routes()
                  .get("/", (_, response) -> response.text("ok"), e -> e.requireInstalled(last));
            }
          };
      app.extensions(first, last);

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
      }
    }
  }

  @Test
  void rejectsReentrantExtensionInstallationDuringOwnershipClaims() throws Exception {
    var nested = new OwnedCapability();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      Extension<Void> first =
          new Extension<>() {
            @Override
            public void claimOwnership() {
              assertThrows(IllegalStateException.class, () -> app.extensions(nested));
            }
          };
      app.extensions(first);
      app.extensions(nested).routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
      }
    }

    assertEquals(1, nested.closes.get());
  }

  @Test
  void rejectsReentrantAuthenticationInstallationDuringOwnershipClaims() throws Exception {
    var owned = new OwnedCapability();
    var nested = owningAuthenticator(owned);

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      Extension<Void> first =
          new Extension<>() {
            @Override
            public void claimOwnership() {
              assertThrows(IllegalStateException.class, () -> app.authentication(nested));
            }
          };
      app.extensions(first);
      app.authentication(nested).routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
      }
    }

    assertEquals(1, owned.closes.get());
  }

  @Test
  void rejectsReentrantStartupDuringOwnershipClaims() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      Extension<Void> first =
          new Extension<>() {
            @Override
            public void claimOwnership() {
              assertThrows(IllegalStateException.class, app::start);
            }
          };
      app.extensions(first).routes().get("/", (_, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/")).statusCode());
      }
    }
  }

  @Test
  void closesASuccessfullyClaimedResourceWhenTheClaimClosesTheApplication() throws Exception {
    var closes = new AtomicInteger();

    try (var app = new Shoostr()) {
      Extension<Void> capability =
          new Extension<>() {
            @Override
            public void claimOwnership() {
              assertDoesNotThrow(app::close);
            }

            @Override
            public void close() {
              closes.incrementAndGet();
            }
          };

      assertThrows(IllegalStateException.class, () -> app.extensions(capability));
      assertEquals(1, closes.get());
    }

    assertEquals(1, closes.get());
  }

  private static AuthenticationExtension owningAuthenticator(OwnedCapability capability) {
    return new AuthenticationExtension() {
      @Override
      public void claimOwnership() {
        capability.claimOwnership();
      }

      @Override
      public void handle(Request request, Response response) {}

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
      app.extensions(capability);
      return true;
    } catch (IllegalStateException rejected) {
      return false;
    }
  }

  private static Extension<Void> loggedCapability(
      String name, List<String> events, boolean failsSetup) {
    return new Extension<>() {
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

  private static final class OwnedCapability implements Extension<Void> {
    private final AtomicBoolean claimed;
    private final AtomicInteger closes;

    private OwnedCapability() {
      claimed = new AtomicBoolean();
      closes = new AtomicInteger();
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
          (_, _) -> {
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
