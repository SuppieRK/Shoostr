package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

class EndpointAuthenticationTest {
  @ParameterizedTest
  @MethodSource("rejections")
  void rejectsSelectedRequestForInvalidCredentialsOrAuthorization(
      String executorType, String rejection, int status) throws Exception {
    var invoked = new AtomicBoolean();
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var authenticated = new CompletableFuture<Thread>();
    var authorized = new CompletableFuture<Thread>();
    var client =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              authenticated.complete(Thread.currentThread());
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"alice".equals(credentials.getUsername())
                  || !"correct".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("alice");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });

    try (var workers = executor(executorType, owned);
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var auth =
          new Pac4j(
              client,
              "Basic",
              (_, _, _) -> {
                authorized.complete(Thread.currentThread());
                return false;
              });
      app.authentication(auth)
          .routes()
          .get(
              "/me",
              workers,
              (_, response) -> {
                invoked.set(true);
                response.text("wrong");
              },
              extensions -> extensions.get(auth).required());
      var credentials =
          "wrong".equals(rejection) ? "Basic YWxpY2U6d3Jvbmc=" : "Basic YWxpY2U6Y29ycmVjdA==";

      try (var test = TestServer.start(app)) {
        assertEquals(
            status,
            test.send(request -> request.path("/me").header("Authorization", credentials))
                .statusCode());
        assertFalse(invoked.get());
        assertTrue(owned.contains(authenticated.get(5, TimeUnit.SECONDS)));
        if (status == 403) {
          assertSame(authenticated.get(5, TimeUnit.SECONDS), authorized.get(5, TimeUnit.SECONDS));
        } else {
          assertFalse(authorized.isDone());
        }
      }

      assertFalse(workers.isShutdown());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"platform", "virtual", "fork-join", "wrapped-fork-join"})
  void authenticatesAndAuthorizesOnTheSelectedExecutor(String executorType) throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();
    var authenticated = new CompletableFuture<Thread>();
    var authorized = new CompletableFuture<Thread>();
    var handled = new CompletableFuture<Thread>();
    var client =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              authenticated.complete(Thread.currentThread());
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"alice".equals(credentials.getUsername())
                  || !"correct".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("alice");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });

    try (var workers = executor(executorType, owned);
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var auth =
          new Pac4j(
              client,
              "Basic",
              (_, _, _) -> {
                authorized.complete(Thread.currentThread());
                return true;
              });
      app.authentication(auth)
          .routes()
          .get(
              "/me",
              workers,
              (request, response) -> {
                handled.complete(Thread.currentThread());
                response.text(request.principal().orElseThrow().getName());
              },
              extensions -> extensions.get(auth).required());

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request.path("/me").header("Authorization", "Basic YWxpY2U6Y29ycmVjdA=="),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("alice", result.body());
        var thread = authenticated.get(5, TimeUnit.SECONDS);
        assertTrue(owned.contains(thread));
        assertSame(thread, authorized.get(5, TimeUnit.SECONDS));
        assertSame(thread, handled.get(5, TimeUnit.SECONDS));
      }

      assertFalse(workers.isShutdown());
    }
  }

  private static ExecutorService executor(String type, Set<Thread> owned) {
    ThreadFactory factory =
        task -> {
          var thread =
              "virtual".equals(type)
                  ? Thread.ofVirtual().unstarted(task)
                  : Thread.ofPlatform().unstarted(task);
          owned.add(thread);
          return thread;
        };
    return switch (type) {
      case "platform" -> Executors.newFixedThreadPool(1, factory);
      case "virtual" -> Executors.newThreadPerTaskExecutor(factory);
      case "fork-join" -> forkJoin(1, owned);
      case "wrapped-fork-join" -> Executors.unconfigurableExecutorService(forkJoin(1, owned));
      default -> throw new IllegalArgumentException(type);
    };
  }

  private static ForkJoinPool forkJoin(int parallelism, Set<Thread> owned) {
    return new ForkJoinPool(
        parallelism,
        pool -> {
          var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
          owned.add(worker);
          return worker;
        },
        null,
        false);
  }

  private static Stream<Arguments> rejections() {
    return Stream.of("platform", "virtual", "fork-join", "wrapped-fork-join")
        .flatMap(
            type ->
                Stream.of(
                    Arguments.of(type, "wrong", 401), Arguments.of(type, "unauthorized", 403)));
  }
}
