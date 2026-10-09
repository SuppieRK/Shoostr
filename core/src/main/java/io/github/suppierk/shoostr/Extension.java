package io.github.suppierk.shoostr;

import java.io.Closeable;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Optional application capability selected by instance during route registration. Installation is
 * eager; route configuration and metadata finish before the listener starts. The application closes
 * installed extensions, but an extension must not close borrowed registries, SDKs or clients.
 *
 * <p>Explicit endpoint execution experiments may hand off between pre-routing and matched
 * callbacks. Do not rely on both phases sharing a thread or on arbitrary thread-local state being
 * copied. Instrumentation must reattach retained context through {@link
 * RequestObservation#attach()} and restore each scope on its owning thread. Request attributes and
 * principals retain their references, not automatic thread safety. Terminal observers retain their
 * separate completion-thread contract.
 *
 * @param <C> provider's typed route configuration
 */
public interface Extension<C> extends Closeable {
  /**
   * Registers application-wide behavior eagerly in written installation order.
   *
   * @param callbacks registration-only callbacks, frozen when installation finishes
   */
  default void install(ApplicationCallbacks callbacks) {}

  /**
   * Creates independent configuration for a scope. Copy inherited values rather than returning or
   * mutating the parent. Register callbacks on the supplied scope; core handles execution phases.
   * This method also runs for inherited selections, without replaying the user's group callback.
   * Providers must capture stable values in runtime callbacks. App-only extensions may leave this
   * method unsupported. Select collaborating extensions separately in the user's route/group
   * callback; nested get on this provider-only contribution scope is rejected.
   *
   * @param endpoint configuration receiving this extension's contributions
   * @param inherited parent configuration, or null for the first selection
   * @return new typed configuration
   * @throws UnsupportedOperationException if this extension has no route configuration
   */
  default C configure(Extensions endpoint, @Nullable C inherited) {
    throw new UnsupportedOperationException("This extension only provides application behavior");
  }

  /** Finalizes metadata and owned resources after route facts arrive, before accepting traffic. */
  default void beforeStart() {}

  /**
   * Releases extension-owned resources once per application installation, in reverse order.
   *
   * @throws IOException if an owned resource cannot be closed
   */
  @Override
  default void close() throws IOException {}
}
