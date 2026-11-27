package io.github.suppierk.shoostr;

/**
 * Renders an exception escaping a request handler before commitment. The callback owns its response
 * content; the framework owns completion and thread confinement.
 *
 * @param <E> exception family accepted by this handler
 */
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {
  /**
   * Writes an error response while the original request remains available on the handler thread.
   *
   * <p>S112 is suppressed due to the need to retain checked exception contracts.
   *
   * @param exception directly thrown application exception
   * @param request original live request
   * @param response response with failed output cleared and the error status initialized
   * @throws Exception if error handling fails; the framework applies its safe fallback
   */
  @SuppressWarnings("java:S112")
  void handle(E exception, Request request, Response response) throws Exception;

  /**
   * Renders an exception with codec-backed body helpers. Register it through {@link Routes.Typed}.
   *
   * @param <E> exception family accepted by this handler
   */
  @FunctionalInterface
  interface Typed<E extends Exception> extends ExceptionHandler<E> {
    /**
     * Writes an error response for the original exchange using the configured codec if needed.
     *
     * @param exception directly thrown application exception
     * @param request original live request
     * @param response response with failed output cleared and the error status initialized
     * @throws Exception if error handling fails; the framework applies its safe fallback
     */
    @SuppressWarnings("java:S112")
    void handle(E exception, Request.Typed request, Response.Typed response) throws Exception;

    /** {@inheritDoc} */
    @Override
    default void handle(E exception, Request request, Response response) throws Exception {
      handle(exception, (Request.Typed) request, (Response.Typed) response);
    }
  }
}
