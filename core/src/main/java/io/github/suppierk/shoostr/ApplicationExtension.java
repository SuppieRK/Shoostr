package io.github.suppierk.shoostr;

import org.jspecify.annotations.Nullable;

/**
 * An extension supporting application-default activation with typed configuration. Shared
 * infrastructure and ownership belong to {@link Extension}; activation contributes behavior only
 * within the selected scope. A provider supporting both roles uses the same configuration type and
 * operation. Supplied SDKs and registries remain caller-owned.
 *
 * @param <C> typed configuration
 */
public non-sealed interface ApplicationExtension<C> extends Extension {
  /**
   * Creates independent configuration and registers stable callbacks for this scope. The operation
   * is also used when copying inherited settings into descendants.
   *
   * @param lifecycle isolated registration collector, frozen after configuration finishes
   * @param inherited enclosing configuration, or null for first selection
   * @return nonnull local configuration
   */
  C configure(Lifecycle lifecycle, @Nullable C inherited);
}
