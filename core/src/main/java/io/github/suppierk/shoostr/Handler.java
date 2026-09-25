package io.github.suppierk.shoostr;

/** Runs on a virtual thread. Request and response belong to this invocation only. */
@FunctionalInterface
public interface Handler {
  /**
   * Handles one request; the framework closes the response after return.
   *
   * @param request handler-scoped input
   * @param response handler-scoped output
   * @throws Exception if request processing fails
   */
  @SuppressWarnings("java:S112") // Application callbacks may propagate their own checked failures.
  void handle(Request request, Response response) throws Exception;
}
