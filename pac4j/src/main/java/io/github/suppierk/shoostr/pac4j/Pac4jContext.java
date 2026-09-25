package io.github.suppierk.shoostr.pac4j;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.pac4j.core.context.Cookie;
import org.pac4j.core.context.WebContext;

/** Adapts the framework-owned request and response without retaining them beyond the handler. */
final class Pac4jContext implements WebContext {
  private final Request request;
  private final Response response;

  /**
   * Creates the provider view for one admitted request.
   *
   * @param request handler-owned request
   * @param response handler-owned response
   */
  Pac4jContext(Request request, Response response) {
    this.request = request;
    this.response = response;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<String> getRequestParameter(String name) {
    var values = getRequestParameters().get(name);
    return values == null || values.length == 0 ? Optional.empty() : Optional.of(values[0]);
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, String[]> getRequestParameters() {
    var parameters = new HashMap<String, String[]>();
    request
        .queryParamMap()
        .forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
    var contentType = request.header(HttpHeaders.CONTENT_TYPE.value());
    if (contentType != null) {
      var separator = contentType.indexOf(';');
      var mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
      if ("application/x-www-form-urlencoded".equalsIgnoreCase(mediaType)
          || "multipart/form-data".equalsIgnoreCase(mediaType)) {
        try {
          request
              .formParamMap()
              .forEach(
                  (name, values) ->
                      parameters.merge(
                          name, values.toArray(String[]::new), Pac4jContext::concatenate));
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
    }

    return parameters;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Object> getRequestAttribute(String name) {
    return Optional.ofNullable(request.attribute(name));
  }

  /** {@inheritDoc} */
  @Override
  public void setRequestAttribute(String name, @Nullable Object value) {
    request.attribute(name, value);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<String> getRequestHeader(String name) {
    return Optional.ofNullable(request.header(name));
  }

  /** {@inheritDoc} */
  @Override
  public String getRequestMethod() {
    return request.method();
  }

  /** {@inheritDoc} */
  @Override
  public String getRemoteAddr() {
    var remote = request.clientAddress();
    return remote == null ? "" : remote.getHostString();
  }

  /** {@inheritDoc} */
  @Override
  public void setResponseHeader(String name, String value) {
    if (value.isEmpty()) {
      response.removeHeader(name);
    } else {
      response.header(name, value);
    }
  }

  /** {@inheritDoc} */
  @Override
  public Optional<String> getResponseHeader(String name) {
    return Optional.ofNullable(response.header(name));
  }

  /** {@inheritDoc} */
  @Override
  public void setResponseContentType(String content) {
    response.header(HttpHeaders.CONTENT_TYPE.value(), content);
  }

  /** {@inheritDoc} */
  @Override
  public String getServerName() {
    return Objects.requireNonNull(URI.create(request.effectiveUrl()).getHost());
  }

  /** {@inheritDoc} */
  @Override
  public int getServerPort() {
    var uri = URI.create(request.effectiveUrl());
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }

    return "https".equals(uri.getScheme()) ? 443 : 80;
  }

  /** {@inheritDoc} */
  @Override
  public String getScheme() {
    return Objects.requireNonNull(URI.create(request.effectiveUrl()).getScheme());
  }

  /** {@inheritDoc} */
  @Override
  public boolean isSecure() {
    return request.isSecure();
  }

  /** {@inheritDoc} */
  @Override
  public String getFullRequestURL() {
    return request.effectiveUrl();
  }

  /** {@inheritDoc} */
  @Override
  public Collection<Cookie> getRequestCookies() {
    return request.cookieMap().entrySet().stream()
        .map(entry -> new Cookie(entry.getKey(), entry.getValue()))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public void addResponseCookie(Cookie cookie) {
    var sameSite =
        cookie.getSameSitePolicy() == null
            ? null
            : io.github.suppierk.shoostr.http.Cookie.SameSite.valueOf(
                cookie.getSameSitePolicy().toUpperCase(Locale.ROOT));
    var converted =
        new io.github.suppierk.shoostr.http.Cookie(
            cookie.getName(),
            cookie.getValue(),
            Objects.requireNonNullElse(cookie.getPath(), HttpCharacters.PATH_SEPARATOR_STRING),
            cookie.getDomain(),
            cookie.getMaxAge(),
            cookie.isSecure(),
            cookie.isHttpOnly(),
            sameSite,
            null);

    response.cookie(converted);
  }

  /** {@inheritDoc} */
  @Override
  public String getPath() {
    return request.path();
  }

  /** {@inheritDoc} */
  @Override
  public String getProtocol() {
    return request.protocol();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<String> getQueryString() {
    return Optional.ofNullable(request.queryString());
  }

  /**
   * Appends form values after query values for the provider's combined parameter view.
   *
   * @param query query values in wire order
   * @param form form values in wire order
   * @return independent array retaining query-first precedence
   */
  private static String[] concatenate(String[] query, String[] form) {
    var combined = Arrays.copyOf(query, query.length + form.length);
    System.arraycopy(form, 0, combined, query.length, form.length);
    return combined;
  }
}
