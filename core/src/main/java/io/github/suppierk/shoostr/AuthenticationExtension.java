package io.github.suppierk.shoostr;

import org.jspecify.annotations.Nullable;

/**
 * A managed identity source. Install through Shoostr.authentication and select with
 * extensions.with(instance). Authentication runs before local and application admission hooks; each
 * endpoint may select only one distinct inherited owner.
 */
public abstract class AuthenticationExtension
    implements Extension<AuthenticationExtension>, Handler {
  /** Creates an identity source without selecting it on any route. */
  protected AuthenticationExtension() {}

  /** {@inheritDoc} */
  @Override
  public final AuthenticationExtension configure(
      Extensions endpoint, @Nullable AuthenticationExtension inherited) {
    endpoint.authentication(this);
    return this;
  }
}
