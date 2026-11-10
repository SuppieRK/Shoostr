package io.github.suppierk.consumer;

import java.io.Closeable;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.jetty.server.ServerConnector;
import io.github.suppierk.shoostr.MultipartOptions;

public final class Main {
  public static void main(String[] args) throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1048576);
    configuredOptions.setMaxResponseBytes(1048576);
    configuredOptions.setStreamBufferBytes(8192);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    { var app = new Shoostr(configuredOptions).modifyServer(nativeServer -> { var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0]; nativeConnector.setHost("127.0.0.1"); nativeConnector.setPort(0); nativeConnector.setIdleTimeout(30000); }); try (Closeable appCleanup = app::close; var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      app.routes().get("/published", (request, response) -> response.text("published"));
      app.start();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/published"))
              .timeout(Duration.ofSeconds(5))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || !"published".equals(response.body())) {
        throw new IllegalStateException("Unexpected published HTTP response: " + response);
      }

      System.out.println("PUBLISHED_HTTP_OK");
    } }
  }
}
