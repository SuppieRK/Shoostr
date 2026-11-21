package io.github.suppierk.shoostr;

import org.jspecify.annotations.Nullable;

/**
 * A managed identity source selected with {@code lifecycle.withExtension(instance)} on a route or
 * group. First selection establishes shared setup automatically. Use {@code
 * app.withExtension(instance)} before route registration to require authentication by default.
 * Authentication runs before local and application admission hooks; each endpoint may select only
 * one distinct inherited owner.
 */
public abstract class AuthenticationExtension
    implements ApplicationExtension<AuthenticationExtension>,
        RouteExtension<AuthenticationExtension>,
        Handler {
  /** Creates an identity source without selecting it on any route. */
  protected AuthenticationExtension() {}

  /** {@inheritDoc} */
  @Override
  public final AuthenticationExtension configure(
      Lifecycle endpoint, @Nullable AuthenticationExtension inherited) {
    endpoint.authentication(this);
    return this;
  }
}
