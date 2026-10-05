package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @NullSource
  void rejectsNullPathNamesBeforeAndAfterRouteSelection(String name) throws Exception {
    app.onRequestHeaders(
        (request, _) -> assertThrows(NullPointerException.class, () -> request.pathParam(name)));
    app.routes()
        .get(
            "/null-name/{id}",
            (request, response) -> {
              assertThrows(NullPointerException.class, () -> request.pathParam(name));
              response.text("rejected");
            });
    app.start();
    var result =
        client.send(request("/null-name/one").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("rejected", result.body());
  }

  @Test
  void representsMissingNativeUriMetadataWithEmptyOptionals() throws Exception {
    app.modifyHttpConfiguration(
        configuration ->
            configuration.addCustomizer(
                (nativeRequest, _) -> {
                  var uri =
                      HttpURI.build(nativeRequest.getHttpURI())
                          .scheme((String) null)
                          .authority(null, -1)
                          .asImmutable();
                  var nativeMetadata = nativeRequest.getConnectionMetaData();
                  var metadata =
                      (ConnectionMetaData)
                          Proxy.newProxyInstance(
                              ConnectionMetaData.class.getClassLoader(),
                              new Class<?>[] {ConnectionMetaData.class},
                              (_, method, arguments) ->
                                  "getServerAuthority".equals(method.getName())
                                      ? null
                                      : method.invoke(nativeMetadata, arguments));
                  return new org.eclipse.jetty.server.Request.Wrapper(nativeRequest) {
                    @Override
                    public HttpURI getHttpURI() {
                      return uri;
                    }

                    @Override
                    public ConnectionMetaData getConnectionMetaData() {
                      return metadata;
                    }
                  };
                }));
    app.routes()
        .get(
            "/missing-native",
            (request, response) -> {
              assertEquals(Optional.empty(), request.scheme());
              assertEquals(Optional.empty(), request.authority());
              assertEquals(Optional.empty(), request.serverName());
              response.text("empty");
            });
    app.start();
    var result =
        client.send(request("/missing-native").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("empty", result.body());
  }

  @Test
  void representsMissingNativeSocketAddressesWithEmptyOptionals() throws Exception {
    app.modifyHttpConfiguration(
        configuration ->
            configuration.addCustomizer(
                (nativeRequest, _) -> {
                  var nativeMetadata = nativeRequest.getConnectionMetaData();
                  var nativeConnection = nativeMetadata.getConnection();
                  var nativeEndpoint = nativeConnection.getEndPoint();
                  var endpoint =
                      (EndPoint)
                          Proxy.newProxyInstance(
                              EndPoint.class.getClassLoader(),
                              new Class<?>[] {EndPoint.class},
                              (_, method, arguments) ->
                                  switch (method.getName()) {
                                    case "getLocalSocketAddress", "getRemoteSocketAddress" -> null;
                                    default -> method.invoke(nativeEndpoint, arguments);
                                  });
                  var connection =
                      (Connection)
                          Proxy.newProxyInstance(
                              Connection.class.getClassLoader(),
                              new Class<?>[] {Connection.class},
                              (_, method, arguments) ->
                                  "getEndPoint".equals(method.getName())
                                      ? endpoint
                                      : method.invoke(nativeConnection, arguments));
                  var metadata =
                      (ConnectionMetaData)
                          Proxy.newProxyInstance(
                              ConnectionMetaData.class.getClassLoader(),
                              new Class<?>[] {ConnectionMetaData.class},
                              (_, method, arguments) ->
                                  "getConnection".equals(method.getName())
                                      ? connection
                                      : method.invoke(nativeMetadata, arguments));
                  return new org.eclipse.jetty.server.Request.Wrapper(nativeRequest) {
                    @Override
                    public ConnectionMetaData getConnectionMetaData() {
                      return metadata;
                    }
                  };
                }));
    app.trustedProxies(InetAddress::isLoopbackAddress);
    app.routes()
        .get(
            "/missing-addresses",
            (request, response) -> {
              assertEquals(Optional.empty(), request.remoteAddress());
              assertEquals(Optional.empty(), request.localAddress());
              assertEquals(Optional.empty(), request.clientAddress());
              assertFalse(request.isForwarded());
              response.text("empty");
            });
    app.start();
    var result =
        client.send(
            request("/missing-addresses")
                .header("Forwarded", "for=203.0.113.7;host=public.example;proto=https")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("empty", result.body());
  }

  @Test
  void exposesRawQueryAndRepeatedHeaderSnapshots() throws Exception {
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertEquals("q=a+b%2F&q=second&empty=", request.queryString().orElseThrow());
              var fields = request.headerMap();
              assertSame(fields, request.headerMap());
              assertEquals(List.of("a,b", "c"), fields.get("X-VALUES"));
              assertNull(fields.get("Missing"));
              assertThrows(UnsupportedOperationException.class, fields::clear);
              var values = Objects.requireNonNull(fields.get("x-values"));
              assertSame(values, request.headers("X-Values"));
              assertThrows(UnsupportedOperationException.class, values::clear);
              assertEquals(List.of("a b/", "second"), request.queryParams("q"));
              assertEquals("q=a+b%2F&q=second&empty=", request.queryString().orElseThrow());
              response.text("ok");
            });
    app.routes()
        .get(
            "/absent",
            (request, response) -> {
              assertTrue(request.queryString().isEmpty());
              response.text("absent");
            });
    app.start();
    var result =
        client.send(
            request("/metadata?q=a+b%2F&q=second&empty=")
                .header("X-Values", "a,b")
                .header("x-values", "c")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("ok", result.body());
    assertEquals(
        "absent",
        client.send(request("/absent").build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test
  void capturesHeadersAndCookiesBeforeAdmissionHooks() throws Exception {
    var transportHeaders = new AtomicReference<HttpFields.Mutable>();
    app.modifyHttpConfiguration(
        configuration ->
            configuration.addCustomizer(
                (nativeRequest, _) -> {
                  var fields = HttpFields.build(nativeRequest.getHeaders());
                  transportHeaders.set(fields);
                  return new org.eclipse.jetty.server.Request.Wrapper(nativeRequest) {
                    @Override
                    public HttpFields getHeaders() {
                      return fields;
                    }
                  };
                }));
    app.onRequestHeaders(
        (_, _) -> {
          transportHeaders.get().put("X-Snapshot", "changed");
          transportHeaders.get().put("Cookie", "token=changed");
        });
    app.routes()
        .get(
            "/snapshot",
            (request, response) ->
                response.text(
                    String.join(
                        ";",
                        request.header("X-Snapshot").map(Object::toString).orElse("null"),
                        request.headers("x-snapshot").toString(),
                        Objects.toString(request.headerMap().get("X-SNAPSHOT")),
                        request.cookie("token").map(Object::toString).orElse("null"),
                        request.cookies("token").toString(),
                        Objects.toString(request.cookieMap().get("token")))));
    app.start();
    var result =
        client.send(
            request("/snapshot")
                .header("X-Snapshot", "original")
                .header("Cookie", "token=first")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("original;[original];[original];first;[first];[first]", result.body());
  }

  @Test
  void exposesEmptyMetadataForHeaderlessRequests() throws Exception {
    app.routes()
        .get(
            "/empty",
            (request, response) ->
                response.text(
                    String.join(
                        ";",
                        request.headerMap().toString(),
                        request.cookieMap().toString(),
                        request.header("Missing").map(Object::toString).orElse("null"),
                        request.headers("Missing").toString(),
                        request.cookie("missing").map(Object::toString).orElse("null"),
                        request.cookies("missing").toString())));
    app.start();
    String result;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write("GET /empty HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      result = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    assertEquals("200", result.split(" ", 3)[1]);
    assertTrue(result.endsWith("{};{};null;[];null;[]"), result);
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
                      assertEquals(request.pathParam("id").orElseThrow(), values.get("id"));
                      assertThrows(UnsupportedOperationException.class, values::clear);
                      retained.set(values);
                      response.text(request.routePattern().orElseThrow());
                    }));
    app.routes()
        .get(
            "/literal",
            (request, response) -> {
              assertEquals(Map.of(), request.pathParamMap());
              response.text("literal");
            });
    app.start();
    var result =
        client.send(
            request("/groups/caf%C3%A9/items/a+b%20c").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("/groups/{group}/items/{id}", result.body());
    assertEquals(
        "literal",
        client.send(request("/literal").build(), HttpResponse.BodyHandlers.ofString()).body());
    assertEquals(Map.of("group", "café", "id", "a+b c"), retained.get());
  }

  @Test
  void distinguishesRequestAuthorityFromDirectConnection() throws Exception {
    var peerPort = new AtomicInteger();
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertEquals("http", request.scheme().orElseThrow());
              assertEquals("virtual.example:8443", request.authority().orElseThrow());
              assertEquals("virtual.example", request.serverName().orElseThrow());
              assertEquals(8443, request.serverPort());
              assertEquals("HTTP/1.1", request.protocol());
              assertFalse(request.isSecure());
              assertEquals("http://virtual.example:8443/metadata", request.url());
              assertEquals("http://virtual.example:8443/metadata?encoded=a%2Bb", request.fullUrl());
              var local =
                  assertInstanceOf(InetSocketAddress.class, request.localAddress().orElseThrow());
              var remote =
                  assertInstanceOf(InetSocketAddress.class, request.remoteAddress().orElseThrow());
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
              assertEquals("https", request.scheme().orElseThrow());
              assertEquals("virtual.example", request.serverName().orElseThrow());
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
        (request, _) -> {
          assertTrue(request.attribute("value").isEmpty());
          assertEquals(Map.of(), request.attributeMap());
          assertSame(request, request.attribute("value", value));
        });
    app.routes()
        .get(
            "/attributes",
            (request, response) -> {
              assertSame(value, request.attribute("value").orElseThrow());
              var snapshot = request.attributeMap();
              retained.set(snapshot);
              assertThrows(UnsupportedOperationException.class, snapshot::clear);
              request.attribute("value", "replacement").attribute("other", 42);
              assertSame(value, snapshot.get("value"));
              assertNull(snapshot.get("other"));
              assertEquals("replacement", request.attribute("value").orElseThrow());
              request.attribute("value", null).attribute("absent", null);
              assertTrue(request.attribute("value").isEmpty());
              assertEquals(Map.of("other", 42), request.attributeMap());
              assertThrows(NullPointerException.class, () -> request.attribute(null));
              assertThrows(NullPointerException.class, () -> request.attribute(null, "bad"));
              response.text("ok");
            });
    app.start();
    assertEquals(
        "ok",
        client.send(request("/attributes").build(), HttpResponse.BodyHandlers.ofString()).body());
    assertSame(value, retained.get().get("value"));
    assertEquals(
        "ok",
        client.send(request("/attributes").build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test
  void propagatesPrincipalAndStateThroughErrorHandling() throws Exception {
    var retained = new AtomicReference<Principal>();
    app.beforeRouteHandler(
        (request, _) -> {
          assertTrue(request.principal().isEmpty());
          if (request.routePattern().filter("/users/{id}"::equals).isPresent()) {
            var name = request.pathParam("id").orElseThrow();
            Principal principal = () -> name;
            assertSame(request, request.principal(principal));
            retained.set(principal);
            request.attribute("trace", "trace-" + name);
          }
        });
    app.exception(
        IllegalArgumentException.class,
        (_, request, response) -> {
          assertEquals("/users/{id}", request.routePattern().orElseThrow());
          assertEquals(Map.of("id", "alice"), request.pathParamMap());
          assertEquals("alice", request.principal().orElseThrow().getName());
          assertEquals("trace-alice", request.attribute("trace").orElseThrow());
          response.status(409).text(request.principal().orElseThrow().getName());
        });
    app.routes()
        .get(
            "/users/{id}",
            (request, _) -> {
              assertSame(retained.get(), request.principal().orElseThrow());
              throw new IllegalArgumentException("business failure");
            });
    app.routes()
        .get(
            "/anonymous",
            (request, response) -> {
              assertTrue(request.principal().isEmpty());
              request.principal(() -> "temporary");
              request.principal(null);
              assertTrue(request.principal().isEmpty());
              assertEquals(Map.of(), request.attributeMap());
              response.text("anonymous");
            });
    app.start();
    var result = client.send(request("/users/alice").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(409, result.statusCode());
    assertEquals("alice", result.body());
    assertEquals("alice", retained.get().getName());
    assertEquals(
        "anonymous",
        client.send(request("/anonymous").build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test
  void isolatesStateAcrossConcurrentRequests() throws Exception {
    var entered = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    app.beforeRouteHandler(
        (request, _) -> {
          assertTrue(request.principal().isEmpty());
          assertEquals(Map.of(), request.attributeMap());
          var id = request.pathParam("id").orElseThrow();
          request.principal(() -> id).attribute("id", id);
        });
    app.routes()
        .get(
            "/concurrent/{id}",
            (request, response) -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              assertEquals(
                  request.pathParam("id").orElseThrow(), request.attribute("id").orElseThrow());
              response.text(request.principal().orElseThrow().getName());
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
    assertEquals(
        "fresh",
        client
            .send(request("/concurrent/fresh").build(), HttpResponse.BodyHandlers.ofString())
            .body());
  }

  @Test
  void normalizesAnExplicitHttpsDefaultPortAndPreservesTheRawQueryOverTls() throws Exception {
    var keyStore = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      keyStore.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keyStore);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    app.tls(
        tls -> {
          tls.setKeyStore(keyStore);
          tls.setKeyStorePassword("changeit");
        });
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertTrue(request.isSecure());
              assertEquals("https", request.scheme().orElseThrow());
              assertEquals("localhost", request.authority().orElseThrow());
              assertEquals("localhost", request.serverName().orElseThrow());
              assertEquals(443, request.serverPort());
              assertEquals("https://localhost/metadata", request.url());
              assertEquals(
                  "https://localhost/metadata?encoded=a%2Bb&empty=&slash=%2f", request.fullUrl());
              assertEquals("encoded=a%2Bb&empty=&slash=%2f", request.queryString().orElseThrow());
              response.text("secure metadata");
            });
    app.start();

    try (var socket = (SSLSocket) context.getSocketFactory().createSocket()) {
      socket.setSoTimeout(3000);
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.startHandshake();
      socket
          .getOutputStream()
          .write(
              String.join(
                      "\r\n",
                      "GET /metadata?encoded=a%2Bb&empty=&slash=%2f HTTP/1.1",
                      "Host: localhost:443",
                      "Connection: close",
                      "",
                      "")
                  .getBytes(StandardCharsets.US_ASCII));
      var result = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
      assertNotNull(result);
      assertEquals(200, result.getStatus());
      assertEquals("secure metadata", result.getContent());
    }
  }

  @Test
  void handlesIpv6AuthoritiesAndMissingLegacyHost() throws Exception {
    app.routes()
        .get(
            "/ipv6",
            (request, response) -> {
              assertEquals("[2001:db8::1]", request.authority().orElseThrow());
              assertEquals("[2001:db8::1]", request.serverName().orElseThrow());
              assertEquals(80, request.serverPort());
              assertEquals("http://[2001:db8::1]/ipv6", request.url());
              assertEquals("", request.queryString().orElseThrow());
              assertEquals("http://[2001:db8::1]/ipv6?", request.fullUrl());
              response.text("ipv6");
            });
    app.routes()
        .get(
            "/legacy",
            (request, response) -> {
              assertEquals("HTTP/1.0", request.protocol());
              assertEquals("127.0.0.1", request.serverName().orElseThrow());
              assertEquals(app.port(), request.serverPort());
              assertEquals("http://127.0.0.1:" + app.port() + "/legacy", request.fullUrl());
              response.text("legacy");
            });
    app.start();
    String ipv6;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /ipv6? HTTP/1.1\r\nHost: [2001:db8::1]:80\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      ipv6 = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(ipv6.startsWith("HTTP/1.1 200"), ipv6);
    assertTrue(ipv6.endsWith("ipv6"), ipv6);
    String legacy;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write("GET /legacy HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      legacy = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

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
              assertEquals("bad=%GG", request.queryString().orElseThrow());
              assertEquals("http://example.test/raw", request.url());
              assertEquals("http://example.test/raw?bad=%GG", request.fullUrl());
              assertThrows(BadRequestException.class, request::queryParamMap);
              assertEquals("bad=%GG", request.queryString().orElseThrow());
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
    String raw;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /raw?bad=%GG HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
    assertTrue(raw.endsWith("raw"), raw);
    var rejected =
        client.send(request("/path/%252e").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(400, rejected.statusCode());
    assertEquals(0, pathCalls.get());
    assertEquals(
        404,
        client
            .send(request("/missing").build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
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
            Request::routePattern,
            request -> request.attribute("state"),
            request -> request.pathParam("missing"),
            request -> request.queryParam("missing"),
            request -> request.header("missing"),
            request -> request.cookie("missing"),
            request -> request.session(false));
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
              assertEquals("value", request.attribute("state").orElseThrow());
              assertEquals("user", request.principal().orElseThrow().getName());
              assertEquals(Map.of("id", "one"), request.pathParamMap());
              stream.write("streamed");
            });
    app.routes().get("/other", (_, response) -> response.text("other"));
    app.start();
    assertEquals(
        "streamed",
        client
            .send(
                request("/lifetime/one").header("X-Value", "kept").build(),
                HttpResponse.BodyHandlers.ofString())
            .body());
    var closedRequest = retained.get();
    for (var reader : readers) {
      assertThrows(IllegalStateException.class, () -> reader.apply(closedRequest));
    }
    assertThrows(IllegalStateException.class, () -> closedRequest.attribute("state", null));
    assertThrows(IllegalStateException.class, () -> closedRequest.principal(null));
    assertEquals(
        "other",
        client
            .send(
                request("/other").header("X-Value", "different").build(),
                HttpResponse.BodyHandlers.ofString())
            .body());
    assertEquals(List.of("kept"), snapshot.get().get("X-VALUE"));
  }

  @Test
  void matchesAnEncodedUtf8LiteralAtTheLiveListener() throws Exception {
    app.routes().get("/tést", (_, response) -> response.text("unicode-literal"));
    app.routes().get("/{value}", (_, response) -> response.text("parameter"));
    app.start();
    var result = client.send(request("/t%C3%A9st").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("unicode-literal", result.body());
  }

  @Test
  void rejectsAnEncodedSlashBeforeInvokingEitherSegmentShape() throws Exception {
    var calls = new AtomicInteger();
    app.routes()
        .get(
            "/segments/{value}",
            (request, response) -> {
              calls.incrementAndGet();
              response.text(request.pathParam("value").orElseThrow());
            });
    app.routes()
        .get(
            "/segments/a/b",
            (_, response) -> {
              calls.incrementAndGet();
              response.text("split");
            });
    app.start();
    var parameter =
        client.send(request("/segments/a").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, parameter.statusCode());
    assertEquals("a", parameter.body());
    var split = client.send(request("/segments/a/b").build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, split.statusCode());
    assertEquals("split", split.body());
    assertEquals(2, calls.get());
    HttpTester.Response rejected;

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /segments/a%2Fb HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      rejected =
          HttpTester.parseResponse(
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    assertNotNull(rejected);
    assertEquals(400, rejected.getStatus());
    assertEquals(2, calls.get());
  }

  @ParameterizedTest
  @CsvSource({"200, false", "200, true", "204, false", "204, true"})
  void reusesHttp11ConnectionsWithoutAdvertisingKeepAlive(int status, boolean keepAlive)
      throws Exception {
    app.routes()
        .get(
            "/response",
            (request, response) -> {
              assertEquals("HTTP/1.1", request.protocol());
              response.status(status);
              if (status == 200) {
                response.text("first");
              }
            });
    app.routes().get("/next", (_, response) -> response.text("next"));
    app.start();

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      var input = HttpTester.from(socket.getInputStream());
      var message =
          "GET /response HTTP/1.1\r\nHost: example.test\r\n"
              + (keepAlive ? "Connection: keep-alive\r\n" : "")
              + "\r\n";
      socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
      var first = HttpTester.parseResponse(input);
      assertNotNull(first);
      assertEquals(status, first.getStatus());
      assertEquals(status == 200 ? "first" : "", first.getContent());
      assertNull(first.get("Connection"));
      socket
          .getOutputStream()
          .write(
              "GET /next HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      var next = HttpTester.parseResponse(input);
      assertNotNull(next);
      assertEquals(200, next.getStatus());
      assertEquals("next", next.getContent());
      assertEquals("close", next.get("Connection"));
      assertEquals(-1, socket.getInputStream().read());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204})
  void closesHttp11ConnectionsWhenTheRequestExplicitlyAsks(int status) throws Exception {
    app.routes()
        .get(
            "/response",
            (request, response) -> {
              assertEquals("HTTP/1.1", request.protocol());
              response.status(status);
              if (status == 200) {
                response.text("first");
              }
            });
    app.start();

    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], app.port()), 3000);
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /response HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      var result = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
      assertNotNull(result);
      assertEquals(status, result.getStatus());
      assertEquals(status == 200 ? "first" : "", result.getContent());
      assertEquals("close", result.get("Connection"));
      assertEquals(-1, socket.getInputStream().read());
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(3));
  }
}
