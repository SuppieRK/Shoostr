package io.github.suppierk.shoostr;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A managed identity source. Install through Shoostr.authentication and select enabled() on routes
 * or groups. Several instances may coexist in an application, but each endpoint may require only
 * one distinct instance. Authentication runs before extension/local/app gates.
 */
public abstract class AuthenticationExtension
    implements Extension<AuthenticationExtension.Requirement>, Handler {
  /** Creates an authentication capability without enabling it on any route. */
  protected AuthenticationExtension() {}

  /** {@inheritDoc} */
  @Override
  public final Requirement configure(Extensions endpoint, @Nullable Requirement inherited) {
    var requirement = new Requirement(endpoint, this);
    if (inherited != null && inherited.required) {
      requirement.enabled();
    }

    return requirement;
  }

  /** Route-local selection of the installed authenticator. */
  public static final class Requirement {
    private final Extensions endpoint;
    private final AuthenticationExtension authentication;
    private boolean required;

    /**
     * Creates a requirement owned by one configuration scope.
     *
     * @param endpoint local registration
     * @param authentication installed identity source
     */
    private Requirement(Extensions endpoint, AuthenticationExtension authentication) {
      this.endpoint = Objects.requireNonNull(endpoint);
      this.authentication = Objects.requireNonNull(authentication);
    }

    /**
     * Requires authentication once before this route's gates; repeated calls are harmless.
     * Conflicting requirements are rejected when the endpoint is bound.
     *
     * @return this configuration
     */
    public Requirement enabled() {
      endpoint.authentication(authentication);
      required = true;
      return this;
    }
  }
}
