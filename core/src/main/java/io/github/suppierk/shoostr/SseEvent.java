package io.github.suppierk.shoostr;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One immutable server-sent event with optional wire metadata.
 *
 * @param data UTF-8 payload
 * @param event optional event name
 * @param id optional event ID
 * @param retry optional client retry delay
 */
public record SseEvent(
    String data, @Nullable String event, @Nullable String id, @Nullable Duration retry) {
  /**
   * Checks the required payload.
   *
   * @param data UTF-8 payload
   * @param event optional event name
   * @param id optional event ID
   * @param retry optional client retry delay
   */
  public SseEvent {
    Objects.requireNonNull(data);
    validateLineField(event);
    validateLineField(id);
    if (retry != null) {
      if (retry.isNegative()) {
        throw new IllegalArgumentException("Retry delay cannot be negative");
      }

      try {
        retry = Duration.ofMillis(retry.toMillis());
      } catch (ArithmeticException failure) {
        throw new IllegalArgumentException("Retry delay exceeds milliseconds range", failure);
      }
    }
  }

  /**
   * Creates a data-only event.
   *
   * @param data UTF-8 payload
   * @return new event
   */
  public static SseEvent of(String data) {
    return new SseEvent(data, null, null, null);
  }

  /**
   * Sets the named event type.
   *
   * @param name event type
   * @return copy with the event type
   */
  public SseEvent withEvent(String name) {
    return new SseEvent(data, Objects.requireNonNull(name), id, retry);
  }

  /**
   * Sets the event ID.
   *
   * @param value ID sent to reconnecting clients
   * @return copy with the ID
   */
  public SseEvent withId(String value) {
    return new SseEvent(data, event, Objects.requireNonNull(value), retry);
  }

  /**
   * Sets the client's retry delay.
   *
   * @param value retry delay
   * @return copy with the retry delay
   */
  public SseEvent withRetry(Duration value) {
    return new SseEvent(data, event, id, Objects.requireNonNull(value));
  }

  /**
   * Rejects characters that would terminate or corrupt a single-line metadata field.
   *
   * @param value optional metadata value
   */
  private static void validateLineField(@Nullable String value) {
    if (value != null
        && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Event metadata must occupy one line");
    }
  }
}
