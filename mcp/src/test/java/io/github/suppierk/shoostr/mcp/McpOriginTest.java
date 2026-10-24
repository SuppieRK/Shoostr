package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Cors;
import io.github.suppierk.shoostr.CorsPolicy;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpOriginTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://untrusted.example",
        "null",
        "https://allowed.example/path",
        "https://allowed.example:",
        "https://user@allowed.example",
        "https://allowed.example?query",
        "https://allowed.example#fragment",
        "https://allowed.example https://untrusted.example"
      })
  void rejectsUnapprovedOrMalformedOriginBeforeManagedAuthentication(String origin)
      throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of("https://allowed.example"),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored2, ignored3) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));

    try (var test = TestServer.start(app)) {
      assertEquals(
          403,
          test.send(request -> request.path("/mcp").method("POST").header("Origin", origin))
              .statusCode());
      assertEquals(0, authentication.attempts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "DELETE", "OPTIONS", "PUT", "PATCH", "HEAD", "TRACE"})
  void rejectsUnapprovedOriginEvenWhenTheMethodDoesNotMatchARoute(String method) throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of("https://allowed.example"),
            ignored4 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored5, ignored6) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));

    try (var test = TestServer.start(app)) {
      assertEquals(
          403,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method(method)
                          .header("Origin", "https://untrusted.example"))
              .statusCode());
      assertEquals(0, authentication.attempts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://allowed.example", "HTTPS://ALLOWED.EXAMPLE:443"})
  void permitsAllowedOriginToReachManagedAuthentication(String origin) throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of("https://allowed.example"),
            ignored7 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored8, ignored9) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));

    try (var test = TestServer.start(app)) {
      assertEquals(
          401,
          test.send(request -> request.path("/mcp").method("POST").header("Origin", origin))
              .statusCode());
      assertEquals(1, authentication.attempts.get());
    }
  }

  @Test
  void permitsAbsentOriginToReachManagedAuthentication() throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored10 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored11, ignored12) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));

    try (var test = TestServer.start(app)) {
      assertEquals(401, test.send(request -> request.path("/mcp").method("POST")).statusCode());
      assertEquals(1, authentication.attempts.get());
    }
  }

  @Test
  void rejectsMultipleOriginHeadersBeforeManagedAuthentication() throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of("https://allowed.example"),
            ignored13 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored14, ignored15) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));

    try (var test = TestServer.start(app)) {
      assertEquals(
          403,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("POST")
                          .header("Origin", "https://allowed.example")
                          .header("Origin", "https://allowed.example"))
              .statusCode());
      assertEquals(0, authentication.attempts.get());
    }
  }

  @Test
  void doesNotApplyMcpOriginPolicyToUnrelatedEndpoints() throws Exception {
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored16 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored17, ignored18) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    app.extensions(mcp)
        .routes(
            routes -> {
              routes.path("/mcp", mcp::routes);
              routes.get("/public", (ignored19, response) -> response.text("public"));
            });

    try (var test = TestServer.start(app)) {
      assertEquals(
          200,
          test.send(
                  request -> request.path("/public").header("Origin", "https://untrusted.example"))
              .statusCode());
    }
  }

  @Test
  void rejectsUnapprovedOriginEvenWhenApplicationCorsWouldAdmitThePreflight() throws Exception {
    var authentication = new RejectingAuthentication();
    var security =
        new McpHttpSecurity(
            Set.of("https://allowed.example"),
            ignored20 -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (ignored21, ignored22) -> {});
    var mcp = Mcp.sync(security, transport -> McpServer.sync(transport).build());
    var app = new Shoostr();
    var cors =
        new Cors(
            new CorsPolicy(
                Set.of("https://untrusted.example"),
                Set.of(HttpMethods.POST),
                Set.of(),
                false,
                Set.of(),
                5));
    app.extensions(cors);
    app.authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path(
                    "/mcp",
                    mcp::routes,
                    extensions -> {
                      extensions.with(authentication);
                      extensions.with(cors);
                    }));

    try (var test = TestServer.start(app)) {
      assertEquals(
          403,
          test.send(
                  request ->
                      request
                          .path("/mcp")
                          .method("OPTIONS")
                          .header("Origin", "https://untrusted.example")
                          .header("Access-Control-Request-Method", "POST"))
              .statusCode());
      assertEquals(0, authentication.attempts.get());
    }
  }

  private static final class RejectingAuthentication extends AuthenticationExtension {
    private final AtomicInteger attempts;

    private RejectingAuthentication() {
      attempts = new AtomicInteger();
    }

    @Override
    public void handle(Request request, Response response) {
      attempts.incrementAndGet();
      throw new UnauthorizedException();
    }
  }
}
