package io.github.suppierk.shoostr;

/** Handler selected through a codec-backed view; ordinary byte/text operations remain available. */
@FunctionalInterface
public interface TypedHandler {
  /**
   * Handles one live exchange on its selected executor. Request/response must not escape to child
   * work or be used after the framework finishes the invocation.
   *
   * @param request typed input sharing the ordinary request lifecycle
   * @param response typed output sharing the ordinary response lifecycle
   * @throws Exception if application handling or codec conversion fails
   */
  @SuppressWarnings("java:S112") // Preserve the same checked exception contract as Handler.
  void handle(TypedRequest request, TypedResponse response) throws Exception;
}
