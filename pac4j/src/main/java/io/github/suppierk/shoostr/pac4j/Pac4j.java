package io.github.suppierk.shoostr.pac4j;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.util.List;
import java.util.Objects;
import org.pac4j.core.authorization.authorizer.Authorizer;
import org.pac4j.core.client.DirectClient;
import org.pac4j.core.context.CallContext;

/**
 * A reusable stateless authentication policy using an application-configured pac4j direct client.
 * Configure the client and its thread-safe authenticator before creating this policy; do not mutate
 * them while serving requests. The application owns credentials, keys, and identity stores.
 */
public final class Pac4j implements Handler {
  private final DirectClient client;
  private final String challenge;
  private final Authorizer authorizer;

  /**
   * Creates a policy which extracts and validates credentials before establishing a principal.
   *
   * @param client configured direct client with an actual authenticator
   * @param challenge WWW-Authenticate value used for missing or invalid credentials
   */
  public Pac4j(DirectClient client, String challenge) {
    this(client, challenge, (context, session, profiles) -> true);
  }

  /**
   * Creates a policy requiring both validated credentials and an explicit permission decision.
   *
   * @param client application-configured direct client
   * @param challenge WWW-Authenticate value for authentication failures
   * @param authorizer application-configured thread-safe authorization decision
   */
  public Pac4j(DirectClient client, String challenge, Authorizer authorizer) {
    this.client = Objects.requireNonNull(client);
    this.challenge = new AuthenticationRequiredException(challenge).challenge();
    this.authorizer = Objects.requireNonNull(authorizer);
    client.init();
  }

  /**
   * Authenticates one request without creating a session or consuming unrelated request content.
   *
   * @param request current request whose principal receives the validated identity
   * @param response response metadata exposed to the configured provider
   * @throws AuthenticationRequiredException if credentials or an unexpired validated profile are
   *     absent
   * @throws ForbiddenException if the validated identity is not authorized
   */
  @Override
  public void handle(Request request, Response response) {
    request.principal(null);
    if (request.headers(HttpHeaders.AUTHORIZATION.value()).size() > 1) {
      throw new AuthenticationRequiredException(challenge);
    }

    var context =
        new CallContext(new Pac4jContext(request, response), StatelessSessionStore.INSTANCE);
    var credentials =
        client.getCredentials(context).flatMap(value -> client.validateCredentials(context, value));
    var profile =
        credentials
            .flatMap(value -> client.getUserProfile(context, value))
            .filter(value -> !value.isExpired())
            .orElseThrow(() -> new AuthenticationRequiredException(challenge));
    request.principal(profile.asPrincipal());
    if (!authorizer.isAuthorized(context.webContext(), context.sessionStore(), List.of(profile))) {
      throw new ForbiddenException();
    }
  }
}
