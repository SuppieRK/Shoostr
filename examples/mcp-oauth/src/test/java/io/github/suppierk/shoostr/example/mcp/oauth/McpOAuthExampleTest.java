package io.github.suppierk.shoostr.example.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class McpOAuthExampleTest {
  @Test
  void challengesUnauthenticatedInitializationWithTheCanonicalMetadataLocation() throws Exception {
    var app =
        McpOAuthExample.application(
            Options.defaults(),
            URI.create("https://localhost:8443/realms/shoostr-mcp"),
            URI.create("https://localhost:9443/mcp"),
            SSLContext.getDefault());

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request ->
                  request
                      .path("/mcp")
                      .method("POST")
                      .header("Content-Type", "application/json")
                      .header("Accept", "application/json, text/event-stream")
                      .body(
                          """
                          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                            "protocolVersion":"2025-11-25","capabilities":{},
                            "clientInfo":{"name":"oauth-fixture","version":"1.0"}}}
                          """
                              .getBytes(StandardCharsets.UTF_8)));

      assertEquals(401, response.statusCode());
      assertEquals(
          "Bearer resource_metadata=\"https://localhost:9443/.well-known/oauth-protected-resource/mcp\"",
          response.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
    }
  }

  @Test
  void servesCanonicalResourceAndIssuerMetadataWithoutAuthentication() throws Exception {
    var app =
        McpOAuthExample.application(
            Options.defaults(),
            URI.create("https://localhost:8443/realms/shoostr-mcp"),
            URI.create("https://localhost:9443/mcp"),
            SSLContext.getDefault());

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> request.path("/.well-known/oauth-protected-resource/mcp"),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      var document = JSONObjectUtils.parse(response.body());
      assertEquals("https://localhost:9443/mcp", document.get("resource"));
      assertEquals(
          List.of("https://localhost:8443/realms/shoostr-mcp"),
          document.get("authorization_servers"));
      assertEquals(List.of("mcp:read"), document.get("scopes_supported"));
      assertEquals(List.of("header"), document.get("bearer_methods_supported"));
      assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
    }
  }

  @Test
  void acceptsRestBasicCredentialsWithoutAcceptingThemForMcp() throws Exception {
    var app =
        McpOAuthExample.application(
            Options.defaults(),
            URI.create("https://localhost:8443/realms/shoostr-mcp"),
            URI.create("https://localhost:9443/mcp"),
            SSLContext.getDefault());
    var credentials =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("rest-demo:rest-demo-local-only".getBytes(StandardCharsets.UTF_8));

    try (var test = TestServer.start(app)) {
      var rest =
          test.send(
              request -> request.path("/api/profile").header("Authorization", credentials),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, rest.statusCode());
      assertEquals("rest-demo", rest.body());

      var mcp = test.send(request -> request.path("/mcp").header("Authorization", credentials));
      assertEquals(401, mcp.statusCode());
      assertEquals(
          "Bearer resource_metadata=\"https://localhost:9443/.well-known/oauth-protected-resource/mcp\"",
          mcp.headers().firstValue("WWW-Authenticate").orElseThrow());
    }
  }
}
