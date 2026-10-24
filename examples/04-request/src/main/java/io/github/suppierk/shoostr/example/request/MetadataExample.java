package io.github.suppierk.shoostr.example.request;

import io.github.suppierk.shoostr.Shoostr;
import java.security.Principal;

/** Distinguishes request metadata from the physical transport and assigned identity. */
public final class MetadataExample {
  /** Prevents construction of this example factory. */
  private MetadataExample() {}

  /**
   * Creates an unstarted application with a diagnostic metadata endpoint.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes.get(
                    "/inspect/{name}",
                    (request, response) ->
                        response.text(
                            String.join(
                                "\n",
                                "method=" + request.method(),
                                "protocol=" + request.protocol(),
                                "path=" + request.path(),
                                "route=" + request.routePattern().orElse("(missing)"),
                                "name=" + request.pathParam("name").orElseThrow(),
                                "query=" + request.queryString().orElse("(missing)"),
                                // URI metadata is not proof of the connection's transport security.
                                "url=" + request.url(),
                                "fullUrl=" + request.fullUrl(),
                                "effectiveUrl=" + request.effectiveUrl(),
                                "scheme=" + request.scheme().orElse("(missing)"),
                                "authority=" + request.authority().orElse("(missing)"),
                                "serverName=" + request.serverName().orElse("(missing)"),
                                "serverPort=" + request.serverPort(),
                                // Socket addresses describe the direct connection, not proxy
                                // claims.
                                "local="
                                    + request
                                        .localAddress()
                                        .map(Object::toString)
                                        .orElse("(missing)"),
                                "remote="
                                    + request
                                        .remoteAddress()
                                        .map(Object::toString)
                                        .orElse("(missing)"),
                                "client="
                                    + request
                                        .clientAddress()
                                        .map(Object::toString)
                                        .orElse("(missing)"),
                                "secure=" + request.isSecure(),
                                "forwarded=" + request.isForwarded(),
                                "principal="
                                    + request
                                        .principal()
                                        .map(Principal::getName)
                                        .orElse("anonymous")))));
  }
}
