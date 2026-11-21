package io.github.suppierk.shoostr;

/**
 * Runs on the registered endpoint's executor, or the application's native Jetty executor when none
 * is selected. Default execution uses platform threads; {@link Options#setVirtualThreads(Boolean)}
 * enables virtual execution. Request and response belong to this invocation only;
 * application-created child work must not retain them. Executor selection covers the matched
 * lifecycle, not transport completion callbacks.
 */
@FunctionalInterface
public interface Handler {
  /**
   * Handles one request; the framework closes the response after return.
   *
   * <p>S112 is suppressed due to the need to retain checked exception contracts.
   *
   * @param request handler-scoped input
   * @param response handler-scoped output
   * @throws Exception if request processing fails
   */
  @SuppressWarnings("java:S112")
  void handle(Request request, Response response) throws Exception;

  /**
   * Handler selected through a codec-backed view; ordinary byte/text operations remain available.
   */
  @FunctionalInterface
  interface Typed {
    /**
     * Handles one live exchange on its selected executor. Request/response must not escape to child
     * work or be used after the framework finishes the invocation.
     *
     * @param request typed input sharing the ordinary request lifecycle
     * @param response typed output sharing the ordinary response lifecycle
     * @throws Exception if application handling or codec conversion fails
     */
    @SuppressWarnings("java:S112")
    // Preserve the same checked exception contract as Handler.
    void handle(Request.Typed request, Response.Typed response) throws Exception;
  }
}
