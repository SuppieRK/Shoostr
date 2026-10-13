package io.github.suppierk.shoostr;

import java.io.Closeable;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Optional application capability selected by instance during route registration. Installation is
 * eager; route configuration and metadata finish before the listener starts. The application closes
 * installed extensions, but an extension must not close borrowed registries, SDKs or clients.
 *
 * <p>Explicit endpoint executors may hand off between pre-routing and matched callbacks. Do not
 * rely on both phases sharing a thread or on arbitrary thread-local state being copied.
 * Instrumentation must reattach retained context through {@link RequestObservation#attach()} and
 * restore each scope on its owning thread. Request attributes and principals retain their
 * references, not automatic thread safety. Terminal observers retain their separate
 * completion-thread contract.
 *
 * @param <C> provider's typed route configuration
 */
public interface Extension<C> extends Closeable {
  /**
   * Claims cleanup responsibility before this instance is installed. The app validates the entire
   * batch first, then claims instances in written order before invoking any installation callback.
   * Returning successfully transfers responsibility to the receiving app, even if subsequent setup
   * fails. A rejected or not-yet-claimed instance is never closed by that app.
   *
   * <p>Providers owning exclusive resources must atomically reject reuse, including concurrent
   * attempts by different apps. A thrown claim must leave existing ownership unchanged and clean
   * any resources acquired by the failed attempt. The default does nothing; it does not enforce
   * exclusive ownership for existing providers. This hook contributes no request-time behavior.
   *
   * @throws IllegalStateException if ownership cannot be transferred
   */
  default void claimOwnership() {}

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
   * Signals owned long-lived work to finish before native graceful shutdown begins. Invoked once in
   * reverse claim order, including cleanup after incomplete installation or startup. Return
   * promptly without waiting for handlers or disposing resources still needed by draining requests
   * and terminal observers; final resource disposal belongs in {@link #close()}.
   *
   * <p>Prevent new work atomically while signalling active work. Never access a live request or
   * response from this shutdown callback. Runtime failures are collected without skipping other
   * extensions, native shutdown or final cleanup. The default contributes no shutdown work.
   */
  default void beforeStop() {}

  /**
   * Releases extension-owned resources once after a successful ownership claim, in reverse claim
   * order, even if installation was never reached. Running handlers on caller-owned endpoint
   * executors may complete after the native graceful drain and this method. Their retained
   * observations must tolerate late cleanup/completion; extensions must not close caller-owned
   * dependencies on their behalf.
   *
   * @throws IOException if an owned resource cannot be closed
   */
  @Override
  default void close() throws IOException {}
}
