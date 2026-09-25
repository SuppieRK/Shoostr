package io.github.suppierk.shoostr;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.io.Content;

/** A handler-lifetime multipart file part that can explicitly transfer its content to a path. */
public final class Upload {
  private final MultiPart.Part delegate;
  private final Runnable access;
  private final List<InputStream> streams;

  /**
   * Creates an upload wrapper scoped to one request's handler lifetime.
   *
   * @param delegate Jetty multipart file part
   * @param access request-lifetime guard
   */
  Upload(MultiPart.Part delegate, Runnable access) {
    this.delegate = delegate;
    this.access = access;
    streams = new ArrayList<>();
  }

  /**
   * Returns the submitted multipart field name.
   *
   * @return exact multipart field name
   */
  public String name() {
    access.run();
    return delegate.getName();
  }

  /**
   * Returns the client-supplied filename, including an empty filename.
   *
   * @return client filename metadata
   */
  public String fileName() {
    access.run();
    return delegate.getFileName();
  }

  /**
   * Returns the declared byte length.
   *
   * @return content byte count
   */
  public long size() {
    access.run();
    return delegate.getLength();
  }

  /**
   * Opens the file content while the handler owns the request.
   *
   * @return readable content
   */
  public InputStream content() {
    access.run();
    var stream =
        new FilterInputStream(Content.Source.asInputStream(delegate.createContentSource())) {
          /** Validates request lifetime before reading one byte. */
          @Override
          public int read() throws IOException {
            access.run();
            return super.read();
          }

          /** Validates request lifetime before reading a byte range. */
          @Override
          public int read(byte[] value, int offset, int length) throws IOException {
            access.run();
            return super.read(value, offset, length);
          }

          /** Validates request lifetime before advancing past bytes. */
          @Override
          public long skip(long count) throws IOException {
            access.run();
            return super.skip(count);
          }
        };
    synchronized (streams) {
      streams.add(stream);
    }

    return stream;
  }

  /**
   * Transfers the content to an application-owned destination path.
   *
   * @param destination application-selected output path
   * @return the supplied destination
   * @throws IOException if the transfer fails
   */
  public Path persistTo(Path destination) throws IOException {
    access.run();
    delegate.writeTo(Objects.requireNonNull(destination));
    return destination;
  }

  /** Closes content streams opened by this upload before its temporary part is released. */
  void close() {
    synchronized (streams) {
      for (var stream : streams) {
        try {
          stream.close();
        } catch (IOException _) {
          // Cleanup continues so every resource gets a close attempt.
        }
      }
      streams.clear();
    }
  }
}
