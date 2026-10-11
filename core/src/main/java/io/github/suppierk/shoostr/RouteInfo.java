package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.Objects;

/**
 * Cold metadata for a successfully registered HTTP/SSE binding. Availability is descriptive only:
 * neither startup nor metadata consumers evaluate request-time conditions.
 *
 * @param method registered HTTP method
 * @param pathTemplate final composed path template
 * @param conditional whether the binding inherits any availability condition
 */
public record RouteInfo(HttpMethods method, String pathTemplate, boolean conditional) {
  /**
   * Creates non-null registration facts with an absolute path template.
   *
   * @param method registered HTTP method
   * @param pathTemplate final composed absolute template
   * @param conditional whether availability is conditional
   * @throws IllegalArgumentException if the template is not absolute
   */
  public RouteInfo {
    Objects.requireNonNull(method);
    Objects.requireNonNull(pathTemplate);
    if (!pathTemplate.startsWith("/")) {
      throw new IllegalArgumentException("Route template must be absolute");
    }
  }
}
