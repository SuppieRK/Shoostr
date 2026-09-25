package io.github.suppierk.shoostr.http;

/** Selects the single forwarding header family accepted from trusted reverse proxies. */
public enum ForwardedHeaders {
  /** Uses the standardized RFC 7239 {@code Forwarded} field. */
  RFC7239,

  /** Uses the legacy {@code X-Forwarded-*} fields with strict per-hop list alignment. */
  X_FORWARDED
}
