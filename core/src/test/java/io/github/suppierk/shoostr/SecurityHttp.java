package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.util.Objects;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class SecurityHttp {
  private SecurityHttp() {}

  static TestServer start(Shoostr app) throws Exception {
    return start(app, ignored -> {});
  }

  static TestServer start(Shoostr app, Consumer<HttpClient.Builder> configure) throws Exception {
    var store = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(SecurityHttp.class.getResourceAsStream("/localhost-test.p12"))) {
      store.load(input, "changeit".toCharArray());
    }

    app.tls(
        tls -> {
          tls.setKeyStore(store);
          tls.setKeyStorePassword("changeit");
        });
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return TestServer.start(
        app,
        client -> {
          client
              .sslContext(context)
              .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE));
          configure.accept(client);
        });
  }
}
