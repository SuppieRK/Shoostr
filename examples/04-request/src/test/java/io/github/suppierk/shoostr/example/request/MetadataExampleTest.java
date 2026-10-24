package io.github.suppierk.shoostr.example.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetadataExampleTest {
  @Test
  void ignoresUntrustedForwardingAndIdentityHeaders() throws Exception {
    try (var test = TestServer.start(MetadataExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/inspect/Ada?raw=a%2Bb")
                      .header("Forwarded", "for=203.0.113.7;host=public.example;proto=https")
                      .header("X-Forwarded-For", "203.0.113.8")
                      .header("X-Forwarded-Proto", "https")
                      .header("X-User", "admin"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var fields = new Properties();
      fields.load(new StringReader(response.body()));

      assertEquals(
          test.baseUri().resolve("/inspect/Ada?raw=a%2Bb").toString(),
          fields.getProperty("effectiveUrl"));
      assertEquals(fields.getProperty("remote"), fields.getProperty("client"));
      assertTrue(fields.getProperty("client").startsWith("/127.0.0.1:"));
      assertEquals("false", fields.getProperty("forwarded"));
      assertEquals("false", fields.getProperty("secure"));
      assertEquals("anonymous", fields.getProperty("principal"));
    }
  }

  @ParameterizedTest
  @CsvSource({"/inspect/Ada, (missing)", "/inspect/Ada?bad=%FF, bad=%FF"})
  void readsRawQueriesWithoutDecodingThem(String path, String query) throws Exception {
    try (var test = TestServer.start(MetadataExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var fields = new Properties();
      fields.load(new StringReader(response.body()));
      assertEquals(query, fields.getProperty("query"));
    }
  }

  @Test
  void distinguishesLogicalAuthorityAndSchemeFromThePhysicalConnection() throws Exception {
    try (var test = TestServer.start(MetadataExample.application());
        var socket =
            new Socket(InetAddress.getAllByName("127.0.0.1")[0], test.baseUri().getPort())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              String.join(
                      "\r\n",
                      "GET https://docs.example:8443/inspect/Ada HTTP/1.1",
                      "Host: docs.example:8443",
                      "Connection: close",
                      "",
                      "")
                  .getBytes(StandardCharsets.US_ASCII));
      var wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
      var fields = new Properties();
      fields.load(new StringReader(wire.substring(wire.indexOf("\r\n\r\n") + 4)));

      assertEquals("docs.example:8443", fields.getProperty("authority"));
      assertEquals("docs.example", fields.getProperty("serverName"));
      assertEquals("8443", fields.getProperty("serverPort"));
      assertEquals("https", fields.getProperty("scheme"));
      assertEquals("false", fields.getProperty("secure"));
      assertEquals("/127.0.0.1:" + test.baseUri().getPort(), fields.getProperty("local"));
      assertEquals("/127.0.0.1:" + socket.getLocalPort(), fields.getProperty("remote"));
    }
  }

  @Test
  void preservesRawUrlSpellingAlongsideDecodedCaptures() throws Exception {
    try (var test =
        TestServer.start(
            MetadataExample.application(), client -> client.version(HttpClient.Version.HTTP_1_1))) {
      var response =
          test.send(
              request -> request.path("/inspect/caf%C3%A9?name=a+b&encoded=%2520"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var fields = new Properties();
      fields.load(new StringReader(response.body()));
      var origin = test.baseUri().resolve("/").toString();

      assertEquals("GET", fields.getProperty("method"));
      assertEquals("HTTP/1.1", fields.getProperty("protocol"));
      assertEquals("/inspect/café", fields.getProperty("path"));
      assertEquals("/inspect/{name}", fields.getProperty("route"));
      assertEquals("café", fields.getProperty("name"));
      assertEquals("name=a+b&encoded=%2520", fields.getProperty("query"));
      assertEquals(origin + "inspect/caf%C3%A9", fields.getProperty("url"));
      assertEquals(
          origin + "inspect/caf%C3%A9?name=a+b&encoded=%2520", fields.getProperty("fullUrl"));
      assertEquals(fields.getProperty("fullUrl"), fields.getProperty("effectiveUrl"));
      assertEquals("http", fields.getProperty("scheme"));
      assertEquals(test.baseUri().getRawAuthority(), fields.getProperty("authority"));
      assertEquals("127.0.0.1", fields.getProperty("serverName"));
      assertEquals(Integer.toString(test.baseUri().getPort()), fields.getProperty("serverPort"));
      assertEquals("/127.0.0.1:" + test.baseUri().getPort(), fields.getProperty("local"));
      assertTrue(fields.getProperty("remote").startsWith("/127.0.0.1:"));
      assertEquals(fields.getProperty("remote"), fields.getProperty("client"));
      assertEquals("false", fields.getProperty("secure"));
      assertEquals("false", fields.getProperty("forwarded"));
      assertEquals("anonymous", fields.getProperty("principal"));
    }
  }
}
