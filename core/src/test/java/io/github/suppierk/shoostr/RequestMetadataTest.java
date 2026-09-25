package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class RequestMetadataTest {
  private Shoostr app;
  private HttpClient client;

  @BeforeEach
  void prepare() {
    app = new Shoostr(Options.defaults().withPort(0));
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
  }

  @AfterEach
  void close() throws Exception {
    try {
      client.close();
    } finally {
      app.close();
    }
  }

  @Test
  void exposesRawQueryAndRepeatedHeaderSnapshots() throws Exception {
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertEquals("q=a+b%2F&q=second&empty=", request.queryString());
              var fields = request.headerMap();
              assertEquals(List.of("a,b", "c"), fields.get("X-VALUES"));
              assertNull(fields.get("Missing"));
              assertThrows(UnsupportedOperationException.class, fields::clear);
              var values = Objects.requireNonNull(fields.get("x-values"));
              assertThrows(UnsupportedOperationException.class, values::clear);
              assertEquals(List.of("a b/", "second"), request.queryParams("q"));
              assertEquals("q=a+b%2F&q=second&empty=", request.queryString());
              response.text("ok");
            });
    app.routes()
        .get(
            "/absent",
            (request, response) -> {
              assertNull(request.queryString());
              response.text("absent");
            });
    app.start();
    var result =
        send(
            request("/metadata?q=a+b%2F&q=second&empty=")
                .header("X-Values", "a,b")
                .header("x-values", "c"));
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
    assertEquals("absent", send(request("/absent")).body());
  }

  @Test
  void snapshotsComposedPathParameters() throws Exception {
    var retained = new AtomicReference<Map<String, String>>();
    app.routes()
        .path(
            "/groups/{group}",
            routes ->
                routes.get(
                    "/items/{id}",
                    (request, response) -> {
                      var values = request.pathParamMap();
                      assertEquals(Map.of("group", "café", "id", "a+b c"), values);
                      assertEquals(request.pathParam("id"), values.get("id"));
                      assertThrows(UnsupportedOperationException.class, values::clear);
                      retained.set(values);
                      response.text(Objects.requireNonNull(request.routePattern()));
                    }));
    app.routes()
        .get(
            "/literal",
            (request, response) -> {
              assertEquals(Map.of(), request.pathParamMap());
              response.text("literal");
            });
    app.start();
    var result = send(request("/groups/caf%C3%A9/items/a+b%20c"));
    assertEquals(200, result.statusCode());
    assertEquals("/groups/{group}/items/{id}", result.body());
    assertEquals("literal", send(request("/literal")).body());
    assertEquals(Map.of("group", "café", "id", "a+b c"), retained.get());
  }

  @Test
  void distinguishesRequestAuthorityFromDirectConnection() throws Exception {
    var peerPort = new AtomicInteger();
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertEquals("http", request.scheme());
              assertEquals("virtual.example:8443", request.authority());
              assertEquals("virtual.example", request.serverName());
              assertEquals(8443, request.serverPort());
              assertEquals("HTTP/1.1", request.protocol());
              assertFalse(request.isSecure());
              assertEquals("http://virtual.example:8443/metadata", request.url());
              assertEquals("http://virtual.example:8443/metadata?encoded=a%2Bb", request.fullUrl());
              var local = assertInstanceOf(InetSocketAddress.class, request.localAddress());
              var remote = assertInstanceOf(InetSocketAddress.class, request.remoteAddress());
              assertEquals(app.port(), local.getPort());
              assertEquals(peerPort.get(), remote.getPort());
              assertEquals("127.0.0.1", local.getAddress().getHostAddress());
              assertEquals("127.0.0.1", remote.getAddress().getHostAddress());
              response.text("metadata");
            });
    app.start();

    try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      socket.setSoTimeout(3000);
      peerPort.set(socket.getLocalPort());
      socket
          .getOutputStream()
          .write(
              String.join(
                      "\r\n",
                      "GET /metadata?encoded=a%2Bb HTTP/1.1",
                      "Host: virtual.example:8443",
                      "Forwarded: for=203.0.113.7;proto=https;host=spoofed.example",
                      "X-Forwarded-For: 203.0.113.8",
                      "X-Forwarded-Host: spoofed.example",
                      "X-Forwarded-Proto: https",
                      "X-Forwarded-Port: 443",
                      "Connection: close",
                      "",
                      "")
                  .getBytes(StandardCharsets.US_ASCII));
      var wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
      assertTrue(wire.endsWith("metadata"), wire);
    }
  }

  @Test
  void doesNotInferTransportSecurityFromAnAbsoluteTarget() throws Exception {
    app.routes()
        .get(
            "/absolute",
            (request, response) -> {
              assertEquals("https", request.scheme());
              assertEquals("virtual.example", request.serverName());
              assertEquals(443, request.serverPort());
              assertFalse(request.isSecure());
              response.text("plain");
            });
    app.start();

    try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              String.join(
                      "\r\n",
                      "GET https://virtual.example/absolute HTTP/1.1",
                      "Host: virtual.example",
                      "Connection: close",
                      "",
                      "")
                  .getBytes(StandardCharsets.US_ASCII));
      var wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
      assertTrue(wire.endsWith("plain"), wire);
    }
  }

  @Test
  @SuppressWarnings("NullAway") // Null keys are deliberately rejected by the request API.
  void managesRequestLocalAttributesWithExplicitNullRemoval() throws Exception {
    var retained = new AtomicReference<Map<String, Object>>();
    var value = new Object();
    app.beforeRouteHandler(
        (request, response) -> {
          assertNull(request.attribute("value"));
          assertEquals(Map.of(), request.attributeMap());
          assertSame(request, request.attribute("value", value));
        });
    app.routes()
        .get(
            "/attributes",
            (request, response) -> {
              assertSame(value, request.attribute("value"));
              var snapshot = request.attributeMap();
              retained.set(snapshot);
              assertThrows(UnsupportedOperationException.class, snapshot::clear);
              request.attribute("value", "replacement").attribute("other", 42);
              assertSame(value, snapshot.get("value"));
              assertNull(snapshot.get("other"));
              assertEquals("replacement", request.attribute("value"));
              request.attribute("value", null).attribute("absent", null);
              assertNull(request.attribute("value"));
              assertEquals(Map.of("other", 42), request.attributeMap());
              assertThrows(NullPointerException.class, () -> request.attribute(null));
              assertThrows(NullPointerException.class, () -> request.attribute(null, "bad"));
              response.text("ok");
            });
    app.start();
    assertEquals("ok", send(request("/attributes")).body());
    assertSame(value, retained.get().get("value"));
    assertEquals("ok", send(request("/attributes")).body());
  }

  @Test
  void propagatesPrincipalAndStateThroughErrorHandling() throws Exception {
    var retained = new AtomicReference<Principal>();
    app.beforeRouteHandler(
        (request, response) -> {
          assertNull(request.principal());
          if ("/users/{id}".equals(request.routePattern())) {
            var name = request.pathParam("id");
            Principal principal = () -> name;
            assertSame(request, request.principal(principal));
            retained.set(principal);
            request.attribute("trace", "trace-" + name);
          }
        });
    app.exception(
        IllegalArgumentException.class,
        (failure, request, response) -> {
          assertEquals("/users/{id}", request.routePattern());
          assertEquals(Map.of("id", "alice"), request.pathParamMap());
          assertEquals("alice", Objects.requireNonNull(request.principal()).getName());
          assertEquals("trace-alice", request.attribute("trace"));
          response.status(409).text(Objects.requireNonNull(request.principal()).getName());
        });
    app.routes()
        .get(
            "/users/{id}",
            (request, response) -> {
              assertSame(retained.get(), request.principal());
              throw new IllegalArgumentException("business failure");
            });
    app.routes()
        .get(
            "/anonymous",
            (request, response) -> {
              assertNull(request.principal());
              request.principal(() -> "temporary");
              request.principal(null);
              assertNull(request.principal());
              assertEquals(Map.of(), request.attributeMap());
              response.text("anonymous");
            });
    app.start();
    var result = send(request("/users/alice"));
    assertEquals(409, result.statusCode());
    assertEquals("alice", result.body());
    assertEquals("alice", retained.get().getName());
    assertEquals("anonymous", send(request("/anonymous")).body());
  }

  @Test
  void isolatesStateAcrossConcurrentRequests() throws Exception {
    var entered = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    app.beforeRouteHandler(
        (request, response) -> {
          assertNull(request.principal());
          assertEquals(Map.of(), request.attributeMap());
          var id = request.pathParam("id");
          request.principal(() -> id).attribute("id", id);
        });
    app.routes()
        .get(
            "/concurrent/{id}",
            (request, response) -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              assertEquals(request.pathParam("id"), request.attribute("id"));
              response.text(Objects.requireNonNull(request.principal()).getName());
            });
    app.start();
    var results = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int index = 0; index < 8; index++) {
      results.add(
          client.sendAsync(
              request("/concurrent/" + index).timeout(Duration.ofSeconds(8)).build(),
              HttpResponse.BodyHandlers.ofString()));
    }

    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }

    for (int index = 0; index < results.size(); index++) {
      var result = results.get(index).get(3, TimeUnit.SECONDS);
      assertEquals(200, result.statusCode());
      assertEquals(Integer.toString(index), result.body());
    }
    assertEquals("fresh", send(request("/concurrent/fresh")).body());
  }

  @Test
  void handlesIpv6AuthoritiesAndMissingLegacyHost() throws Exception {
    app.routes()
        .get(
            "/ipv6",
            (request, response) -> {
              assertEquals("[2001:db8::1]", request.authority());
              assertEquals("[2001:db8::1]", request.serverName());
              assertEquals(80, request.serverPort());
              assertEquals("http://[2001:db8::1]/ipv6", request.url());
              assertEquals("", request.queryString());
              assertEquals("http://[2001:db8::1]/ipv6?", request.fullUrl());
              response.text("ipv6");
            });
    app.routes()
        .get(
            "/legacy",
            (request, response) -> {
              assertEquals("HTTP/1.0", request.protocol());
              assertEquals("127.0.0.1", request.serverName());
              assertEquals(app.port(), request.serverPort());
              assertEquals("http://127.0.0.1:" + app.port() + "/legacy", request.fullUrl());
              response.text("legacy");
            });
    app.start();
    var ipv6 =
        exchange("GET /ipv6? HTTP/1.1\r\nHost: [2001:db8::1]:80\r\nConnection: close\r\n\r\n");
    assertTrue(ipv6.startsWith("HTTP/1.1 200"), ipv6);
    assertTrue(ipv6.endsWith("ipv6"), ipv6);
    var legacy = exchange("GET /legacy HTTP/1.0\r\n\r\n");
    assertTrue(legacy.contains(" 200 "), legacy);
    assertTrue(legacy.endsWith("legacy"), legacy);
  }

  @Test
  void preservesRawMetadataAndExistingTransportRejections() throws Exception {
    var pathCalls = new AtomicInteger();
    app.routes()
        .get(
            "/raw",
            (request, response) -> {
              assertEquals("bad=%GG", request.queryString());
              assertEquals("http://example.test/raw", request.url());
              assertEquals("http://example.test/raw?bad=%GG", request.fullUrl());
              assertThrows(BadRequestException.class, request::queryParamMap);
              assertEquals("bad=%GG", request.queryString());
              response.text("raw");
            });
    app.routes()
        .get(
            "/path/{id}",
            (request, response) -> {
              pathCalls.incrementAndGet();
              response.text(request.pathParamMap().toString());
            });
    app.start();
    var raw =
        exchange("GET /raw?bad=%GG HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n");
    assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
    assertTrue(raw.endsWith("raw"), raw);
    var rejected = send(request("/path/%252e"));
    assertEquals(400, rejected.statusCode());
    assertEquals(0, pathCalls.get());
    assertEquals(404, send(request("/missing")).statusCode());
  }

  @Test
  void confinesMetadataAndStateToTheLiveHandlerThread() throws Exception {
    var retained = new AtomicReference<Request>();
    var snapshot = new AtomicReference<Map<String, List<String>>>();
    List<Function<Request, Object>> readers =
        List.of(
            Request::queryString,
            Request::headerMap,
            Request::pathParamMap,
            Request::url,
            Request::fullUrl,
            Request::scheme,
            Request::authority,
            Request::serverName,
            Request::serverPort,
            Request::protocol,
            Request::isSecure,
            Request::localAddress,
            Request::remoteAddress,
            Request::isForwarded,
            Request::clientAddress,
            Request::effectiveUrl,
            Request::attributeMap,
            Request::principal,
            request -> request.attribute("state"));
    app.routes()
        .get(
            "/lifetime/{id}",
            (request, response) -> {
              retained.set(request);
              snapshot.set(request.headerMap());
              request.attribute("state", "value").principal(() -> "user");

              try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor
                    .submit(
                        () -> {
                          for (var reader : readers) {
                            assertThrows(IllegalStateException.class, () -> reader.apply(request));
                          }
                          assertThrows(
                              IllegalStateException.class, () -> request.attribute("state", "bad"));
                          assertThrows(IllegalStateException.class, () -> request.principal(null));
                        })
                    .get(3, TimeUnit.SECONDS);
              }

              var stream = response.startStream("text/plain");
              assertEquals("value", request.attribute("state"));
              assertEquals("user", Objects.requireNonNull(request.principal()).getName());
              assertEquals(Map.of("id", "one"), request.pathParamMap());
              stream.write("streamed");
            });
    app.routes().get("/other", (request, response) -> response.text("other"));
    app.start();
    assertEquals("streamed", send(request("/lifetime/one").header("X-Value", "kept")).body());
    var closedRequest = retained.get();
    for (var reader : readers) {
      assertThrows(IllegalStateException.class, () -> reader.apply(closedRequest));
    }
    assertThrows(IllegalStateException.class, () -> closedRequest.attribute("state", null));
    assertThrows(IllegalStateException.class, () -> closedRequest.principal(null));
    assertEquals("other", send(request("/other").header("X-Value", "different")).body());
    assertEquals(List.of("kept"), snapshot.get().get("X-VALUE"));
  }

  private String exchange(String message) throws Exception {
    try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
      socket.setSoTimeout(3000);
      socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
