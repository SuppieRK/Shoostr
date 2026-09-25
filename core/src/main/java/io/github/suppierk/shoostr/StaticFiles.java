package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.io.Closeable;
import java.io.IOException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.jspecify.annotations.Nullable;

/** A frozen filesystem or classpath directory mounted below one application path. */
final class StaticFiles implements Closeable {
  private static final String BINARY_CONTENT_TYPE = "application/octet-stream";
  private final String mount;
  private final List<Handler> policies;
  private final StaticOptions options;
  private final @Nullable SecureDirectoryStream<Path> rootDirectory;
  private final ResourceFactory.@Nullable Closeable resourceFactory;
  private final @Nullable Resource source;

  /**
   * Validates and anchors a filesystem directory mount at registration time.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @throws IllegalArgumentException if the source cannot be resolved, is not a directory, or its
   *     filesystem cannot anchor relative operations safely
   */
  StaticFiles(String mount, Path directory) {
    this(mount, directory, List.of());
  }

  /**
   * Creates a mount with inherited route admission policies.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param policies immutable policies required before serving a resource
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, Path directory, List<Handler> policies) {
    this(mount, directory, policies, StaticOptions.defaults());
  }

  /**
   * Creates a filesystem mount with inherited policies and explicit resource options.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param policies immutable policies required before serving a resource
   * @param options welcome-file and SPA fallback options
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, Path directory, List<Handler> policies, StaticOptions options) {
    this.policies = List.copyOf(policies);
    this.mount = Objects.requireNonNull(mount);
    this.options = Objects.requireNonNull(options);
    Path root;

    try {
      root = Objects.requireNonNull(directory).toRealPath();
    } catch (IOException failure) {
      throw new IllegalArgumentException("Static-file source cannot be resolved", failure);
    }

    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Static-file source must be a directory");
    }

    try {
      rootDirectory = openSecureDirectory(root);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Static-file source cannot be opened", failure);
    }

    resourceFactory = null;
    source = null;
  }

  /**
   * Validates and fixes one classpath directory mount at registration time.
   *
   * @param mount mounted request path
   * @param directory classpath source directory
   * @throws IllegalArgumentException if the source is unavailable or not a readable directory
   */
  StaticFiles(String mount, String directory) {
    this(mount, directory, List.of());
  }

  /**
   * Creates a mount with inherited route admission policies.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param policies immutable policies required before serving a resource
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, String directory, List<Handler> policies) {
    this(mount, directory, policies, StaticOptions.defaults());
  }

  /**
   * Creates a classpath mount with inherited policies and explicit resource options.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param policies immutable policies required before serving a resource
   * @param options welcome-file and SPA fallback options
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, String directory, List<Handler> policies, StaticOptions options) {
    this.policies = List.copyOf(policies);
    this.mount = Objects.requireNonNull(mount);
    this.options = Objects.requireNonNull(options);
    rootDirectory = null;
    resourceFactory = ResourceFactory.closeable();
    source = resourceFactory.newClassLoaderResource(Objects.requireNonNull(directory));
    if (source == null || !source.exists() || !source.isDirectory() || !source.isReadable()) {
      resourceFactory.close();
      throw new IllegalArgumentException("Static classpath source must be a readable directory");
    }
  }

  /**
   * Retains an independent descriptor while always closing the stream used to check capabilities.
   *
   * @param root resolved filesystem directory
   * @return descriptor retained until this mount closes
   * @throws IOException if a directory descriptor cannot be opened or closed
   * @throws IllegalArgumentException if the filesystem lacks secure directory operations
   */
  private static SecureDirectoryStream<Path> openSecureDirectory(Path root) throws IOException {
    SecureDirectoryStream<Path> retained = null;

    try (var stream = Files.newDirectoryStream(root)) {
      if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
        throw new IllegalArgumentException(
            "Static-file source requires secure directory operations");
      }

      retained = secure.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | RuntimeException | Error failure) {
      if (retained != null) {
        try {
          retained.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }

      throw failure;
    }

    return Objects.requireNonNull(retained);
  }

  /**
   * Returns the mounted classpath archive filesystem for lifecycle verification.
   *
   * @return archive filesystem, or null for filesystem mounts and classpath directories
   */
  @Nullable FileSystem archiveFileSystem() {
    var path = source == null ? null : source.getPath();
    return path == null || path.getFileSystem().equals(java.nio.file.FileSystems.getDefault())
        ? null
        : path.getFileSystem();
  }

  /**
   * Returns a synthetic endpoint for one existing GET or HEAD resource. The resource is opened by
   * the endpoint only after hooks and gates have admitted the request.
   *
   * @param path request path
   * @param method requested HTTP method
   * @return synthetic endpoint, or null when this mount has no matching resource
   * @throws IOException if resource metadata cannot be read
   */
  RadixRoutes.@Nullable Endpoint endpoint(String path, @Nullable HttpMethods method)
      throws IOException {
    if (method == null || (method != HttpMethods.GET && method != HttpMethods.HEAD)) {
      return null;
    }

    var selected = select(path);
    if (selected == null) {
      return null;
    }

    return new RadixRoutes.Endpoint(
        method,
        mount,
        mount,
        Routes.protectedHandler((_, response) -> serve(selected, response), policies),
        Map.of(),
        -1,
        0,
        null);
  }

  /**
   * Releases the anchored filesystem directory or classpath archive resources.
   *
   * @throws IllegalStateException if the filesystem source cannot be closed
   */
  @Override
  public void close() {
    IOException failure = null;
    if (rootDirectory != null) {
      try {
        rootDirectory.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
    }

    if (resourceFactory != null) {
      resourceFactory.close();
    }

    if (failure != null) {
      throw new IllegalStateException("Could not close static-file source", failure);
    }
  }

  /**
   * Resolves and stages a static resource after all route admission callbacks have completed.
   *
   * @param relative validated resource selected during route matching
   * @param response response receiving the selected resource
   * @throws NotFoundException if the resource disappears after route matching
   * @throws Exception if the resource cannot be staged
   */
  private void serve(String relative, Response response) throws Exception {
    if (rootDirectory == null) {
      serveClasspath(relative, response);
      return;
    }

    var selected = filesystemResource(relative);
    if (selected == null) {
      throw new NotFoundException();
    }

    try {
      response
          .header(HttpHeaders.CACHE_CONTROL.value(), "no-cache")
          .resource(
              selected.channel(),
              selected.length(),
              selected.lastModified(),
              contentType(selected.name()));
    } catch (Exception failure) {
      selected.close();
      throw failure;
    }
  }

  /**
   * Stages one classpath resource selected after route admission.
   *
   * @param relative validated resource path
   * @param response response receiving the selected resource
   * @throws NotFoundException if the resource disappears after route matching
   * @throws Exception if the resource cannot be staged
   */
  private void serveClasspath(String relative, Response response) throws Exception {
    var resource = Objects.requireNonNull(source).resolve(relative);
    if (!resource.exists()
        || resource.isDirectory()
        || !resource.isReadable()
        || resource.isAlias()) {
      throw new NotFoundException();
    }

    response
        .header(HttpHeaders.CACHE_CONTROL.value(), "no-cache")
        .resource(resource, contentType(resource.getFileName()));
  }

  /**
   * Reports whether an existing GET resource is available without opening a file before gates run.
   *
   * @param path request path
   * @return true when this mount contains an existing resource
   */
  boolean exists(String path) {
    return select(path) != null;
  }

  /**
   * Resolves one existing file while retaining the selected target across route admission. Welcome
   * files are checked beneath a requested directory; SPA fallback is checked at the mount root only
   * when no requested file or welcome file exists.
   *
   * @param path request path
   * @return validated relative file path, or null when this mount cannot serve the request
   */
  private @Nullable String select(String path) {
    var requested = relative(path);
    if (requested == null) {
      return null;
    }

    if (!requested.isEmpty()
        && !requested.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
        && available(requested)) {
      return requested;
    }

    String welcome = options.welcomeFile();
    if (welcome != null) {
      String directory =
          requested.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
              ? requested.substring(0, requested.length() - 1)
              : requested;
      String candidate =
          directory.isEmpty() ? welcome : directory + HttpCharacters.PATH_SEPARATOR + welcome;
      if (available(candidate)) {
        return candidate;
      }
    }

    String fallback = options.spaFallback();
    return fallback != null && available(fallback) ? fallback : null;
  }

  /**
   * Checks one validated relative file without opening a response-owned descriptor.
   *
   * @param relative validated file path
   * @return true when a readable regular resource exists
   */
  private boolean available(String relative) {
    if (!safe(relative)) {
      return false;
    }

    try {
      if (rootDirectory == null) {
        var resource = Objects.requireNonNull(source).resolve(relative);
        return resource.exists()
            && !resource.isDirectory()
            && resource.isReadable()
            && !resource.isAlias();
      }

      return filesystemAttributes(relative) != null;
    } catch (IOException _) {
      return false;
    }
  }

  /**
   * Opens an anchored filesystem resource and captures its descriptor-relative metadata.
   *
   * @param relative validated relative resource path
   * @return selected resource, or null when unavailable
   * @throws IOException if descriptor-relative access fails
   */
  private @Nullable Selected filesystemResource(String relative) throws IOException {
    synchronized (Objects.requireNonNull(rootDirectory)) {
      var directories = directories(relative);
      if (directories == null) {
        return null;
      }

      SeekableByteChannel channel = null;
      Selected selected = null;
      Throwable operationFailure = null;

      try {
        var file = fileName(relative);
        var attributes = attributes(directories.getLast(), file);
        if (attributes != null) {
          channel =
              directories
                  .getLast()
                  .newByteChannel(
                      Path.of(file), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          var revalidated = attributes(directories.getLast(), file);
          if (revalidated != null && sameSelection(attributes, revalidated)) {
            selected =
                new Selected(
                    channel, channel.size(), revalidated.lastModifiedTime().toInstant(), file);
            channel = null;
          }
        }
      } catch (IOException | RuntimeException | Error failure) {
        operationFailure = failure;
      }

      var cleanupFailure = closeSelection(channel, selected, directories);
      if (operationFailure != null) {
        if (cleanupFailure != null) {
          operationFailure.addSuppressed(cleanupFailure);
        }

        if (operationFailure instanceof IOException failure) {
          throw failure;
        }

        if (operationFailure instanceof RuntimeException failure) {
          throw failure;
        }

        throw (Error) operationFailure;
      }

      if (cleanupFailure != null) {
        throw cleanupFailure;
      }

      return selected;
    }
  }

  /**
   * Releases untransferred file and nested-directory descriptors after one selection attempt.
   *
   * @param channel opened file descriptor not yet transferred, or null
   * @param selected selected descriptor transferred to a caller, or null
   * @param directories root-to-parent descriptors
   * @return aggregated cleanup failure, or null when all descriptors closed
   */
  private static @Nullable IOException closeSelection(
      @Nullable SeekableByteChannel channel,
      @Nullable Selected selected,
      List<SecureDirectoryStream<Path>> directories) {
    IOException failure = null;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
    }

    try {
      closeNestedDirectories(directories);
    } catch (IOException closeFailure) {
      if (failure == null) {
        failure = closeFailure;
      } else {
        failure.addSuppressed(closeFailure);
      }
    }

    if (failure != null && selected != null) {
      try {
        selected.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }

    return failure;
  }

  /**
   * Reads anchored metadata without opening a file descriptor during route lookup.
   *
   * @param relative validated relative resource path
   * @return regular-file metadata, or null when unavailable
   * @throws IOException if descriptor-relative access fails
   */
  private @Nullable BasicFileAttributes filesystemAttributes(String relative) throws IOException {
    synchronized (Objects.requireNonNull(rootDirectory)) {
      var directories = directories(relative);
      if (directories == null) {
        return null;
      }

      try {
        return attributes(directories.getLast(), fileName(relative));
      } finally {
        closeNestedDirectories(directories);
      }
    }
  }

  /**
   * Traverses child directories through the retained root descriptor without following links.
   *
   * @param relative validated relative resource path
   * @return root-to-parent descriptors, or null when a path component is unavailable
   * @throws IOException if descriptor traversal fails
   */
  private @Nullable List<SecureDirectoryStream<Path>> directories(String relative)
      throws IOException {
    var directories = new ArrayList<SecureDirectoryStream<Path>>();
    directories.add(Objects.requireNonNull(rootDirectory));
    var segments = relative.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);

    try {
      for (int index = 0; index < segments.length - 1; index++) {
        var nested =
            directories
                .getLast()
                .newDirectoryStream(Path.of(segments[index]), LinkOption.NOFOLLOW_LINKS);
        directories.add(nested);
      }

      return directories;
    } catch (NoSuchFileException | NotDirectoryException _) {
      closeNestedDirectories(directories);
      return null;
    } catch (IOException | RuntimeException failure) {
      closeNestedDirectories(directories);
      throw failure;
    }
  }

  /**
   * Reads metadata for one non-symlink regular leaf through its parent descriptor.
   *
   * @param directory anchored parent descriptor
   * @param file leaf filename
   * @return metadata, or null when the leaf is missing, linked, or not a regular file
   * @throws IOException if descriptor-relative metadata cannot be read
   */
  private static @Nullable BasicFileAttributes attributes(
      SecureDirectoryStream<Path> directory, String file) throws IOException {
    try {
      var view =
          directory.getFileAttributeView(
              Path.of(file), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      var attributes = view.readAttributes();
      return attributes.isRegularFile() ? attributes : null;
    } catch (NoSuchFileException _) {
      return null;
    }
  }

  /**
   * Compares metadata captured on both sides of opening a leaf descriptor.
   *
   * @param first metadata before opening the descriptor
   * @param second metadata after opening the descriptor
   * @return true when both reads identify the same directory entry
   */
  private static boolean sameSelection(BasicFileAttributes first, BasicFileAttributes second) {
    return Objects.equals(first.fileKey(), second.fileKey())
        && first.lastModifiedTime().equals(second.lastModifiedTime())
        && first.size() == second.size();
  }

  /**
   * Closes child descriptors while retaining the mounted root descriptor for later requests.
   *
   * @param directories root-to-parent descriptors
   * @throws IOException if a child descriptor cannot be closed
   */
  private static void closeNestedDirectories(List<SecureDirectoryStream<Path>> directories)
      throws IOException {
    IOException failure = null;
    for (int index = directories.size() - 1; index > 0; index--) {
      try {
        directories.get(index).close();
      } catch (IOException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Extracts a safe decoded relative resource path from this mount.
   *
   * @param path request path
   * @return validated decoded relative path, or null when it cannot be mounted safely
   */
  private @Nullable String relative(String path) {
    if (!mounted(path)) {
      return null;
    }

    String encoded;
    if (mount.length() == 1) {
      encoded = path.substring(1);
    } else if (path.equals(mount)) {
      encoded = "";
    } else {
      encoded = path.substring(mount.length() + 1);
    }

    String decoded;

    try {
      decoded =
          URLDecoder.decode(
              encoded.replace(HttpCharacters.PLUS_SIGN_STRING, HttpCharacters.PERCENT_ENCODED_PLUS),
              StandardCharsets.UTF_8);
    } catch (IllegalArgumentException _) {
      return null;
    }

    if (decoded.isEmpty()) {
      return decoded;
    }

    if (encoded.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
        && decoded.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)) {
      return safe(decoded.substring(0, decoded.length() - 1)) ? decoded : null;
    }

    return safe(decoded) ? decoded : null;
  }

  /**
   * Reports whether a request path is at or below this mount without matching sibling prefixes.
   *
   * @param path request path
   * @return true when the path is at or below this mount
   */
  private boolean mounted(String path) {
    return path.equals(mount)
        || (path.startsWith(mount)
            && path.length() > mount.length()
            && (mount.length() == 1
                || path.charAt(mount.length()) == HttpCharacters.PATH_SEPARATOR));
  }

  /**
   * Selects a deterministic MIME type while keeping unknown files safely binary.
   *
   * @param name selected resource filename
   * @return detected or fallback media type
   */
  private static String contentType(String name) {
    return Objects.requireNonNullElse(
        URLConnection.guessContentTypeFromName(name), BINARY_CONTENT_TYPE);
  }

  /**
   * Returns the leaf filename from a validated slash-separated relative resource path.
   *
   * @param relative validated relative resource path
   * @return leaf filename
   */
  private static String fileName(String relative) {
    return relative.substring(relative.lastIndexOf(HttpCharacters.PATH_SEPARATOR) + 1);
  }

  /**
   * Rejects traversal, empty segments and platform separators before resource resolution.
   *
   * @param path decoded relative path
   * @return true when the path contains no traversal or platform separator
   */
  private static boolean safe(String path) {
    if (path.indexOf('\\') >= 0 || path.indexOf('\u0000') >= 0 || path.isEmpty()) {
      return false;
    }

    for (var segment : path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        return false;
      }
    }

    return true;
  }

  /** One anchored filesystem descriptor and its captured metadata. */
  private record Selected(
      SeekableByteChannel channel, long length, Instant lastModified, String name)
      implements Closeable {
    /**
     * Validates the captured descriptor and metadata before response ownership changes.
     *
     * @param channel open resource descriptor
     * @param length captured byte length
     * @param lastModified captured modification time
     * @param name selected resource name
     * @throws IllegalArgumentException if length is negative
     */
    private Selected {
      Objects.requireNonNull(channel);
      Objects.requireNonNull(lastModified);
      Objects.requireNonNull(name);
      if (length < 0) {
        throw new IllegalArgumentException("Selected resource length cannot be negative");
      }
    }

    /**
     * Releases the descriptor when a response does not take ownership.
     *
     * @throws IOException if the descriptor cannot be closed
     */
    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
