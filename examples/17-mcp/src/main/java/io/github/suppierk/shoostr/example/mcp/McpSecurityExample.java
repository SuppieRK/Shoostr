package io.github.suppierk.shoostr.example.mcp;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.mcp.Mcp;
import io.github.suppierk.shoostr.mcp.McpHttpSecurity;
import io.github.suppierk.shoostr.mcp.McpIdentity;
import io.github.suppierk.shoostr.mcp.McpSessionLimits;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Demonstrates MCP transport security with local development credentials. */
public final class McpSecurityExample {
  private static final String ALICE_SUBJECT = "alice";
  private static final String DESKTOP_CLIENT = "desktop";
  private static final String READ_TOOLS_SCOPE = "tools:read";
  private static final String MCP_ACCESS_SCOPE = "mcp:access";
  private static final String TOKEN_CONTEXT_KEY = "token";

  private static final URI METADATA =
      URI.create("https://resource.example/.well-known/oauth-protected-resource");
  private static final String CHALLENGE =
      "Bearer realm=\"mcp\", resource_metadata=\"" + METADATA + "\"";

  /** Prevents construction of the factory. */
  private McpSecurityExample() {}

  /**
   * Creates an unstarted application with a protected MCP endpoint.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    var issued = Instant.now();
    // This local registry simulates verified claims; production code must verify real credentials.
    var tokens =
        Map.of(
            "reader-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE, READ_TOOLS_SCOPE),
                    issued.plusSeconds(3600)),
            "restricted-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE),
                    issued.plusSeconds(3600)),
            "refreshed-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE, READ_TOOLS_SCOPE),
                    issued.plusSeconds(7200)),
            "other-client-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    "mobile",
                    Set.of(MCP_ACCESS_SCOPE, READ_TOOLS_SCOPE),
                    issued.plusSeconds(3600)),
            "other-user-token",
                new VerifiedToken(
                    "bob",
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE, READ_TOOLS_SCOPE),
                    issued.plusSeconds(3600)),
            "short-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE, READ_TOOLS_SCOPE),
                    issued.plusSeconds(6)),
            "blocked-token",
                new VerifiedToken(
                    ALICE_SUBJECT, DESKTOP_CLIENT, Set.of(), issued.plusSeconds(3600)),
            "expired-token",
                new VerifiedToken(
                    ALICE_SUBJECT,
                    DESKTOP_CLIENT,
                    Set.of(MCP_ACCESS_SCOPE),
                    issued.minusSeconds(1)));
    // Verify identity and transport access before MCP message parsing or session admission.
    var authentication =
        new AuthenticationExtension() {
          /** {@inheritDoc} */
          @Override
          public void handle(Request request, Response response) {
            request.principal(null);
            var values = request.headers("Authorization");
            if (values.size() != 1) {
              throw new AuthenticationRequiredException(CHALLENGE);
            }

            var fields = values.getFirst().split(" +", 2);
            if (fields.length != 2 || !"Bearer".equalsIgnoreCase(fields[0])) {
              throw new AuthenticationRequiredException(CHALLENGE);
            }

            var token = tokens.get(fields[1]);
            if (token == null || !Instant.now().isBefore(token.expiresAt())) {
              throw new AuthenticationRequiredException(CHALLENGE);
            }

            if (!token.scopes().contains(MCP_ACCESS_SCOPE)) {
              throw McpHttpSecurity.scopeDenied(Set.of(MCP_ACCESS_SCOPE), METADATA);
            }

            request.principal(token);
          }
        };
    var security =
        new McpHttpSecurity(
            Set.of("https://client.example"),
            request -> {
              var token = (VerifiedToken) request.principal().orElseThrow();
              // Stable subject/client ownership allows refresh but prevents cross-client session
              // reuse.
              return new McpIdentity(
                  token.owner(),
                  McpTransportContext.create(Map.of(TOKEN_CONTEXT_KEY, token)),
                  token.expiresAt());
            },
            (identity, message) -> {
              // Check the current request's verified claims, not permissions cached in the session.
              var token =
                  Objects.requireNonNull((VerifiedToken) identity.context().get(TOKEN_CONTEXT_KEY));
              if (!token.scopes().contains(MCP_ACCESS_SCOPE)) {
                throw McpHttpSecurity.scopeDenied(Set.of(MCP_ACCESS_SCOPE), METADATA);
              }

              if (message instanceof McpSchema.JSONRPCRequest rpc
                  && rpc.method().startsWith("tools/")
                  && !token.scopes().contains(READ_TOOLS_SCOPE)) {
                throw McpHttpSecurity.scopeDenied(Set.of(READ_TOOLS_SCOPE), METADATA);
              }
            },
            METADATA);
    var mcp =
        Mcp.sync(
            security,
            // Bound sessions globally and per owner, and expire idle sessions after ten seconds.
            new McpSessionLimits(4, 2, Duration.ofSeconds(10)),
            transport ->
                McpServer.sync(transport)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(
                        McpServerFeatures.SyncToolSpecification.builder()
                            .tool(
                                McpSchema.Tool.builder("identity", Map.of("type", "object"))
                                    .build())
                            .callHandler(
                                (exchange, ignored) -> {
                                  var current =
                                      Objects.requireNonNull(
                                          (VerifiedToken)
                                              exchange.transportContext().get(TOKEN_CONTEXT_KEY));
                                  return McpSchema.CallToolResult.builder()
                                      .addTextContent(current.subject() + "/" + current.client())
                                      .build();
                                })
                            .build())
                    .build());
    return new Shoostr()
        .authentication(authentication)
        .extensions(mcp)
        .routes(
            routes ->
                routes.path("/mcp", mcp::routes, extensions -> extensions.with(authentication)));
  }

  /** Verified claims from the local demonstration registry, never request-owned objects. */
  private record VerifiedToken(String subject, String client, Set<String> scopes, Instant expiresAt)
      implements Principal {
    /**
     * Validates identity and snapshots scopes; expiry freshness is checked for each request.
     *
     * @param subject authenticated subject in this registry's identity namespace
     * @param client authenticated client identifier
     * @param scopes verified permissions
     * @param expiresAt fixed credential expiry
     * @throws IllegalArgumentException if the subject or client identifier is blank
     */
    private VerifiedToken {
      Objects.requireNonNull(subject);
      Objects.requireNonNull(client);
      if (subject.isBlank() || client.isBlank()) {
        throw new IllegalArgumentException("Subject and client must not be blank");
      }

      scopes = Set.copyOf(scopes);
      Objects.requireNonNull(expiresAt);
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
      return subject;
    }

    /**
     * Keeps credential refresh separate from the stable subject/client session owner.
     *
     * @return collision-free owner key within this registry
     */
    private String owner() {
      // Token text and expiry deliberately do not enter the stable ownership key.
      var encoder = Base64.getUrlEncoder().withoutPadding();
      return encoder.encodeToString(subject.getBytes(StandardCharsets.UTF_8))
          + ":"
          + encoder.encodeToString(client.getBytes(StandardCharsets.UTF_8));
    }
  }
}
