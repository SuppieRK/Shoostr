package io.github.suppierk.shoostr;

/**
 * Application-wide safety net for an exception escaping a request handler before commitment. The
 * callback owns its response content; the framework owns completion and thread confinement.
 *
 * @param <E> exception family accepted by this handler
 */
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {
  /**
   * Writes an error response while the original request remains available on the handler thread.
   *
   * @param exception directly thrown application exception
   * @param request original live request
   * @param response response with failed output cleared and the error status initialized
   * @throws Exception if error handling fails; the framework applies its safe fallback
   */
  @SuppressWarnings("java:S112") // Applications retain their checked exception contracts.
  void handle(E exception, Request request, Response response) throws Exception;
}
