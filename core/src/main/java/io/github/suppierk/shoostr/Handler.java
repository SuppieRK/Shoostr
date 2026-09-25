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
  void handle(Request request, Response response) throws Exception;
}
