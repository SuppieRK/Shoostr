package io.github.suppierk.consumer;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

public final class Main {
  public static void main(String[] args) throws Exception {
    var configuredOptions = options();
    var app = new Shoostr(configuredOptions).modifyServer(Main::configureListener);

    try (Closeable appCleanup = app::close;
        var client = createClient()) {
      app.routes().get("/published", (request, response) -> response.text("published"));

      app.start();

      var request = publishedRequest(app.port());
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200 || !"published".equals(response.body())) {
        throw new IllegalStateException("Unexpected published HTTP response: " + response);
      }

      System.out.println("PUBLISHED_HTTP_OK");
    }
  }

  private static Options options() {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1048576);
    configuredOptions.setMaxResponseBytes(1048576);
    configuredOptions.setStreamBufferBytes(8192);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    return configuredOptions;
  }

  private static void configureListener(Server nativeServer) {
    var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
    nativeConnector.setHost("127.0.0.1");
    nativeConnector.setPort(0);
    nativeConnector.setIdleTimeout(30000);
  }

  private static HttpClient createClient() {
    return clientProperties().build();
  }

  private static HttpClient.Builder clientProperties() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
  }

  private static HttpRequest publishedRequest(int port) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/published"))
        .timeout(Duration.ofSeconds(5))
        .build();
  }
}
