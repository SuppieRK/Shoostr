package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TransportConfigurationTest {
  @Test
  void bindsReorderedListenersToAutomaticLoopbackPortsAndReportsTheOriginalPrimary()
      throws Exception {
    var listeners = new AtomicReference<List<ServerConnector>>();
    var app = new Shoostr();
    app.modifyServer(
        server -> {
          var primary = (ServerConnector) server.getConnectors()[0];
          var extra = new ServerConnector(server, new HttpConnectionFactory());
          primary.setHost("0.0.0.0");
          primary.setPort(19001);
          extra.setHost("0.0.0.0");
          extra.setPort(19002);
          server.setConnectors(new Connector[] {extra, primary});
          listeners.set(List.of(primary, extra));
        });
    app.routes().get("/value", (_, response) -> response.text("both listeners"));

    try (var test = TestServer.start(app)) {
      assertEquals(listeners.get().getFirst().getLocalPort(), test.baseUri().getPort());
      assertEquals(app.port(), test.baseUri().getPort());
      assertNotEquals(
          listeners.get().getFirst().getLocalPort(), listeners.get().getLast().getLocalPort());
      for (var listener : listeners.get()) {
        assertEquals("127.0.0.1", listener.getHost());
        var target = URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/value");
        assertEquals(
            "both listeners",
            test.httpClient()
                .send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsMultipleListenersBeforeChangingTheirAddresses(boolean range) throws Exception {
    var listeners = new AtomicReference<List<ServerConnector>>();

    try (var app = new Shoostr()) {
      app.modifyServer(
          server -> {
            var primary = (ServerConnector) server.getConnectors()[0];
            var extra = new ServerConnector(server, new HttpConnectionFactory());
            primary.setHost("0.0.0.0");
            primary.setPort(19001);
            extra.setHost("0.0.0.0");
            extra.setPort(19002);
            server.addConnector(extra);
            listeners.set(List.of(primary, extra));
          });
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            if (range) {
              TestServer.startOnPortRange(app, 19003, 19004);
            } else {
              TestServer.startOnPort(app, 19003);
            }
          });
      assertEquals(19001, listeners.get().getFirst().getPort());
      assertEquals(19002, listeners.get().getLast().getPort());
      for (var listener : listeners.get()) {
        assertEquals("0.0.0.0", listener.getHost());
        assertEquals(-1, listener.getLocalPort());
      }
    }
  }

  @Test
  void preservesConfiguredHttp2Negotiation() throws Exception {
    var app = new Shoostr().http2();
    app.routes().get("/value", (_, response) -> response.text("http2"));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/value"));
      assertEquals(200, reply.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, reply.version());
    }
  }

  @Test
  void preservesApplicationBodyLimitsWhileChangingItsTestAddress() throws Exception {
    var app = new Shoostr(new Options("0.0.0.0", 8080, 16, 1024, 64, 30000));
    app.routes().post("/body", (_, response) -> response.text("accepted"));

    try (var test = TestServer.start(app)) {
      var reply = test.send(request -> request.path("/body").method("POST").body(new byte[17]));
      assertEquals(413, reply.statusCode());
    }
  }

  @Test
  void preservesVerifiedTlsAndHttp2WithAPreboundRangePort(@TempDir Path temporary)
      throws Exception {
    var storePath = temporary.resolve("localhost.p12");
    var keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var generator =
        new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "local",
                "-keyalg",
                "RSA",
                "-storetype",
                "PKCS12",
                "-keystore",
                storePath.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=ip:127.0.0.1,dns:localhost",
                "-validity",
                "1",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();

    try {
      assertTrue(generator.waitFor(10, TimeUnit.SECONDS), "keytool must finish within ten seconds");
      assertEquals(0, generator.exitValue());
    } finally {
      generator.destroyForcibly();
    }

    var store = KeyStore.getInstance("PKCS12");

    try (var input = Files.newInputStream(storePath)) {
      store.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    var app = new Shoostr().http2();
    app.tls(
        tls -> {
          tls.setKeyStore(store);
          tls.setKeyStorePassword("changeit");
        });
    app.routes().get("/value", (_, response) -> response.text("verified TLS"));

    try (var test =
        TestServer.startOnPortRange(app, 49152, 65535, client -> client.sslContext(context))) {
      assertEquals("https", test.baseUri().getScheme());
      var reply =
          test.send(request -> request.path("/value"), HttpResponse.BodyHandlers.ofString());
      assertEquals("verified TLS", reply.body());
      assertEquals(HttpClient.Version.HTTP_2, reply.version());
      assertTrue(reply.sslSession().isPresent());
    }
  }
}
