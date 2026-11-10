package io.github.suppierk.shoostr.extensions;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Lifecycle;
import java.util.Objects;

public record AdmissionExtension(Handler callback) implements TestExtension<Handler> {
  public AdmissionExtension {
    Objects.requireNonNull(callback);
  }

  @Override
  public Handler configure(Lifecycle endpoint, Handler inherited) {
    endpoint.beforeRouteHandler(callback);

    return callback;
  }
}
