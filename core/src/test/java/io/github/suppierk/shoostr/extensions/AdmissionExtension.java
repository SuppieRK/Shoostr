package io.github.suppierk.shoostr.extensions;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Handler;
import java.util.Objects;

public record AdmissionExtension(Handler callback) implements Extension<Handler> {
  public AdmissionExtension {
    Objects.requireNonNull(callback);
  }

  @Override
  public Handler configure(Extensions endpoint, Handler inherited) {
    endpoint.beforeRouteHandler(callback);
    return callback;
  }
}
