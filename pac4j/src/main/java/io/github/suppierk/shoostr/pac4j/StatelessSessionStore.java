package io.github.suppierk.shoostr.pac4j;

import java.util.Optional;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;

/** Refuses session creation or mutation for the stateless direct-client integration. */
enum StatelessSessionStore implements SessionStore {
  INSTANCE;

  /** {@inheritDoc} */
  @Override
  public Optional<String> getSessionId(WebContext context, boolean createSession) {
    if (createSession) {
      throw new UnsupportedOperationException("This pac4j integration is stateless");
    }

    return Optional.empty();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Object> get(WebContext context, String key) {
    return Optional.empty();
  }

  /** {@inheritDoc} */
  @Override
  public void set(WebContext context, String key, Object value) {
    throw new UnsupportedOperationException("This pac4j integration is stateless");
  }

  /** {@inheritDoc} */
  @Override
  public boolean destroySession(WebContext context) {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Object> getTrackableSession(WebContext context) {
    return Optional.empty();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<SessionStore> buildFromTrackableSession(
      WebContext context, Object trackableSession) {
    return Optional.empty();
  }

  /** {@inheritDoc} */
  @Override
  public boolean renewSession(WebContext context) {
    return false;
  }
}
