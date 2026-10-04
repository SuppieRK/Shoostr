package io.github.suppierk.shoostr;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Immutable request-time behavior for one endpoint binding, assembled during registration and
 * reused by matching requests. Endpoints without local runtime behavior retain no behavior object.
 *
 * <p>This record and its frozen collections add retained router state, not per-request construction
 * or extension-registry lookup. Registration creates temporary configuration objects; runtime hooks
 * and collection iteration may still allocate. Callbacks can also retain application-owned objects,
 * so their captures affect the live heap traversed by garbage collection.
 */
record EndpointBehavior(
    @Nullable Handler authentication,
    List<Handler> matched,
    List<Handler> before,
    List<Handler> after,
    List<Handler> beforeFlush,
    List<Handler> afterFlush,
    List<Consumer<RequestOutcome>> observers,
    Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors,
    Map<Integer, Handler> statuses,
    List<Predicate<Request>> conditions) {
  /**
   * Freezes registration collections before publishing the endpoint.
   *
   * @param authentication managed authentication, or null
   * @param matched local match hooks
   * @param before local admission hooks
   * @param after local successful-handler hooks
   * @param beforeFlush local pre-flush hooks
   * @param afterFlush local post-flush hooks
   * @param observers terminal observers
   * @param errors local exception renderers
   * @param statuses local generated-status renderers
   * @param conditions runtime availability checks
   */
  EndpointBehavior {
    matched = List.copyOf(matched);
    before = List.copyOf(before);
    after = List.copyOf(after);
    beforeFlush = List.copyOf(beforeFlush);
    afterFlush = List.copyOf(afterFlush);
    observers = List.copyOf(observers);
    errors = Map.copyOf(errors);
    statuses = Map.copyOf(statuses);
    conditions = List.copyOf(conditions);
  }
}
