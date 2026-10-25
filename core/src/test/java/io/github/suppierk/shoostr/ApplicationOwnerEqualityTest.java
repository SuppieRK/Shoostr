package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import nl.jqno.equalsverifier.api.ConfiguredEqualsVerifier;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.Test;

class ApplicationOwnerEqualityTest {
  @Test
  void retainsApplicationHandlerTaskAndCompletionIdentityThroughExecutionAndShutdown()
      throws Exception {
    var nativeServers = new ArrayList<Server>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();
    var exchanges = new ArrayList<Map.Entry<Request, Response>>();
    var tasks = new ArrayList<Object>();
    var observations = new ArrayList<Object>();
    var hashes = new HashMap<Object, Integer>();
    var entered = List.of(new CountDownLatch(1), new CountDownLatch(1));
    var release = List.of(new CountDownLatch(1), new CountDownLatch(1));

    try (var firstExecutor = Executors.newSingleThreadExecutor();
        var secondExecutor = Executors.newSingleThreadExecutor();
        var first = new Shoostr().modifyServer(nativeServers::add).afterRequest(completed::add);
        var second = new Shoostr().modifyServer(nativeServers::add).afterRequest(completed::add);
        var client = HttpClient.newHttpClient()) {
      var apps = List.of(first, second);
      var executors = List.of(firstExecutor, secondExecutor);
      for (int index = 0; index < apps.size(); index++) {
        var position = index;
        var app = apps.get(index);
        hashes.put(app, app.hashCode());
        app.routes()
            .get(
                "/same",
                executors.get(index),
                (request, response) -> {
                  exchanges.add(Map.entry(request, response));
                  entered.get(position).countDown();
                  assertTrue(release.get(position).await(5, TimeUnit.SECONDS));
                  response.text("done");
                });
      }

      try (var firstServer = TestServer.start(first);
          var secondServer = TestServer.start(second)) {
        var servers = List.of(firstServer, secondServer);
        for (int index = 0; index < servers.size(); index++) {
          var response =
              client.sendAsync(
                  HttpRequest.newBuilder(servers.get(index).baseUri().resolve("/same"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());

          try {
            assertTrue(entered.get(index).await(3, TimeUnit.SECONDS));
            // Capture real running owners as verifier fixtures while the public handler is paused.
            var task = pendingTask(apps.get(index));
            tasks.add(task);
            hashes.put(task, task.hashCode());
            var observation = field(task, "observation");
            assertNotNull(observation);
            observations.add(observation);
            hashes.put(observation, observation.hashCode());
          } finally {
            release.get(index).countDown();
          }

          assertEquals("done", response.get(3, TimeUnit.SECONDS).body());
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          // The barrier runs after the matched task has discarded its request references.
          executors.get(index).submit(() -> {}).get(3, TimeUnit.SECONDS);
        }
        var handlerType = Class.forName(Shoostr.class.getName() + "$ApplicationHandler");
        var firstHandler =
            nativeServers
                .get(0)
                .getDescendant(handlerType.asSubclass(org.eclipse.jetty.server.Handler.class));
        var secondHandler =
            nativeServers
                .get(1)
                .getDescendant(handlerType.asSubclass(org.eclipse.jetty.server.Handler.class));
        assertNotNull(firstHandler);
        assertNotNull(secondHandler);
        hashes.put(firstHandler, firstHandler.hashCode());
        hashes.put(secondHandler, secondHandler.hashCode());
        assertNotEquals(first, second);
        assertNotEquals(firstHandler, secondHandler);
        assertNotEquals(tasks.get(0), tasks.get(1));
        assertNotEquals(observations.get(0), observations.get(1));
        var configuration =
            EqualsVerifier.configure()
                .withPrefabValues(Shoostr.class, first, second)
                .withPrefabValues(Routes.class, first.routes(), second.routes())
                .withPrefabValues(
                    Request.class, exchanges.get(0).getKey(), exchanges.get(1).getKey())
                .withPrefabValues(
                    Response.class, exchanges.get(0).getValue(), exchanges.get(1).getValue())
                .withPrefabValues(Server.class, nativeServers.get(0), nativeServers.get(1))
                .withPrefabValues(
                    ServerConnector.class,
                    (ServerConnector) nativeServers.get(0).getConnectors()[0],
                    (ServerConnector) nativeServers.get(1).getConnectors()[0])
                .withPrefabValues(
                    org.eclipse.jetty.server.Handler.class, firstHandler, secondHandler)
                .withPrefabValues(
                    RadixRoutes.class,
                    RadixRoutes.from(
                        List.of(
                            RadixRoutes.endpoint(
                                HttpMethods.GET, "/first", (request, response) -> {}))),
                    RadixRoutes.from(
                        List.of(
                            RadixRoutes.endpoint(
                                HttpMethods.POST, "/second", (request, response) -> {}))))
                .withPrefabValues(
                    RadixRoutes.Endpoint.class,
                    RadixRoutes.endpoint(HttpMethods.GET, "/first", (request, response) -> {}),
                    RadixRoutes.endpoint(HttpMethods.POST, "/second", (request, response) -> {}))
                .withPrefabValues(
                    TrustedProxy.class,
                    new TrustedProxy(InetAddress::isLoopbackAddress, ForwardedHeaders.RFC7239),
                    new TrustedProxy(InetAddress::isSiteLocalAddress, ForwardedHeaders.X_FORWARDED))
                .withPrefabValues(
                    Extension.class,
                    new Cors(CorsPolicy.builder().origins("https://first.example").build()),
                    new Cors(CorsPolicy.builder().origins("https://second.example").build()))
                .withPrefabValues(SessionHandler.class, new SessionHandler(), new SessionHandler())
                .withPrefabValues(
                    CompressionHandler.class, new CompressionHandler(), new CompressionHandler())
                .withPrefabValues(
                    WebSocketRuntime.class,
                    new WebSocketRuntime(firstExecutor),
                    new WebSocketRuntime(secondExecutor))
                .withPrefabValues(ExecutorService.class, firstExecutor, secondExecutor)
                .withPrefabValues(
                    SslContextFactory.Server.class,
                    new SslContextFactory.Server(),
                    new SslContextFactory.Server())
                .withPrefabValues(AutoLock.class, new AutoLock(), new AutoLock())
                .withPrefabValues(
                    Thread.class, new Thread(() -> {}, "first"), new Thread(() -> {}, "second"))
                .withPrefabValues(
                    Csrf.class,
                    new Csrf(Set.of("https://first.example")),
                    new Csrf(Set.of("https://second.example")))
                .withPrefabValues(
                    Throwable.class,
                    new IllegalStateException("first"),
                    new IllegalStateException("second"))
                .suppress(
                    Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED);
        configuration = prefab(configuration, handlerType, firstHandler, secondHandler);
        configuration =
            prefab(configuration, tasks.getFirst().getClass(), tasks.get(0), tasks.get(1));
        configuration =
            prefab(
                configuration,
                observations.getFirst().getClass(),
                observations.get(0),
                observations.get(1));
        configuration.forClass(Shoostr.class).verify();
        configuration.forClass(handlerType).verify();
        configuration.forClass(tasks.getFirst().getClass()).verify();
      }
    }

    var owners = new HashSet<>(hashes.keySet());
    for (var entry : hashes.entrySet()) {
      assertEquals(entry.getKey(), entry.getKey());
      assertEquals(entry.getValue().intValue(), entry.getKey().hashCode());
      assertTrue(owners.remove(entry.getKey()));
    }
    assertTrue(owners.isEmpty());
  }

  private static Object pendingTask(Shoostr app) throws ReflectiveOperationException {
    var pending = (Set<?>) field(app, "pendingMatches");
    synchronized (pending) {
      return pending.iterator().next();
    }
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static <T> ConfiguredEqualsVerifier prefab(
      ConfiguredEqualsVerifier configuration, Class<T> type, Object first, Object second) {
    return configuration.withPrefabValues(type, type.cast(first), type.cast(second));
  }
}
