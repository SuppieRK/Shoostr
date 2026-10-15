package io.github.suppierk.shoostr;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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
    List<Function<Request, RequestObservation>> instrumentation,
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
   * @param instrumentation matched-request observation factories
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
    instrumentation = List.copyOf(instrumentation);
    errors = Map.copyOf(errors);
    statuses = Map.copyOf(statuses);
    conditions = List.copyOf(conditions);
  }

  /**
   * Adds resolved scope renderers during startup while preserving all previously bound phases.
   *
   * @param behavior existing local phases, or null
   * @param errors immutable resolved scope renderers
   * @return completed behavior, unchanged when no scope renderer applies
   */
  static @Nullable EndpointBehavior withErrors(
      @Nullable EndpointBehavior behavior,
      Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors) {
    if (errors.isEmpty()) {
      return behavior;
    }

    return behavior == null
        ? new EndpointBehavior(
            null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            errors, Map.of(), List.of())
        : new EndpointBehavior(
            behavior.authentication,
            behavior.matched,
            behavior.before,
            behavior.after,
            behavior.beforeFlush,
            behavior.afterFlush,
            behavior.observers,
            behavior.instrumentation,
            errors,
            behavior.statuses,
            behavior.conditions);
  }
}
