package io.github.suppierk.shoostr.extensions;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Lifecycle;
import java.util.Objects;

// Shared by test packages. Test sources deliberately contain no Javadocs.
@SuppressWarnings("checkstyle:MissingJavadocType")
public record AdmissionExtension(Handler callback) implements TestExtension<Handler> {
  @SuppressWarnings("checkstyle:MissingJavadocMethod")
  public AdmissionExtension {
    Objects.requireNonNull(callback);
  }

  @Override
  public Handler configure(Lifecycle endpoint, Handler inherited) {
    endpoint.beforeRouteHandler(callback);

    return callback;
  }
}
