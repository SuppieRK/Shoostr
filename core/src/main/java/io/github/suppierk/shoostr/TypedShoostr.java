package io.github.suppierk.shoostr;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Registration view over one existing app; startup, configuration and ownership stay on Shoostr.
 */
public final class TypedShoostr {
  private final Shoostr app;
  private final TypedRoutes routes;

  /**
   * Creates the codec-backed view without acquiring any application resources.
   *
   * @param app original application
   */
  TypedShoostr(Shoostr app) {
    this.app = Objects.requireNonNull(app);
    routes = new TypedRoutes(app.routes());
  }

  /**
   * Returns this view's root registration scope.
   *
   * @return typed registration using the app's existing root routes
   */
  public TypedRoutes routes() {
    return routes;
  }

  /**
   * Registers typed endpoints immediately and returns the original app for fluent configuration and
   * startup. Exceptions and active-registration startup guards match ordinary routes.
   *
   * @param registration typed route definitions
   * @return the original application
   */
  public Shoostr routes(Consumer<TypedRoutes> registration) {
    Objects.requireNonNull(registration);
    app.routes(_ -> registration.accept(routes));
    return app;
  }
}
