package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.ForwardedHeaders;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;

/** Effective request metadata from an explicit set of trusted proxy addresses. */
public final class TrustedProxyExample {
  /** Prevents utility-class construction. */
  private TrustedProxyExample() {}

  /**
   * Captures an immutable peer allowlist and accepts only the selected forwarding header family.
   *
   * @param trustedPeers addresses of proxies controlled by the application owner
   * @param family selected forwarding header family
   * @return unstarted, caller-owned application
   */
  public static Shoostr application(Set<InetAddress> trustedPeers, ForwardedHeaders family) {
    var peers = Set.copyOf(trustedPeers);
    var selectedFamily = Objects.requireNonNull(family);
    return new Shoostr()
        // Check the direct socket peer before accepting forwarding metadata.
        .trustedProxies(peers::contains, selectedFamily)
        .routes(
            routes ->
                routes.get(
                    "/metadata",
                    (request, response) ->
                        response.text(
                            String.join(
                                "\n",
                                "client="
                                    + request
                                        .clientAddress()
                                        .map(InetSocketAddress::getHostString)
                                        .orElse("unknown"),
                                // Transport TLS stays distinct from a trusted proxy's HTTPS claim.
                                "transportSecure=" + request.isSecure(),
                                "effectiveSecure=" + request.isEffectivelySecure(),
                                "url=" + request.effectiveUrl()))));
  }
}
