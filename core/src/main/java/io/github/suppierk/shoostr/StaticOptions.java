package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Optional welcome-file and SPA behavior for a static-resource mount. */
public final class StaticOptions {
  private static final StaticOptions DEFAULTS = new StaticOptions(null, null);

  private final @Nullable String welcomeFile;
  private final @Nullable String spaFallback;

  /**
   * Creates immutable options after validating each enabled filename.
   *
   * @param welcomeFile filename served from a requested directory, or null when disabled
   * @param spaFallback filename served from the mount root for a missing resource, or null when
   *     disabled
   */
  private StaticOptions(@Nullable String welcomeFile, @Nullable String spaFallback) {
    this.welcomeFile = welcomeFile == null ? null : validFileName(welcomeFile);
    this.spaFallback = spaFallback == null ? null : validFileName(spaFallback);
  }

  /**
   * Keeps directory welcome files and SPA fallback disabled.
   *
   * @return immutable default options
   */
  public static StaticOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Serves this filename inside an existing requested directory, including the mount root.
   *
   * @param fileName simple filename without path separators or traversal syntax
   * @return options with the welcome filename configured
   * @throws IllegalArgumentException if the filename is unsafe
   */
  public StaticOptions withWelcomeFile(String fileName) {
    return new StaticOptions(Objects.requireNonNull(fileName), spaFallback);
  }

  /**
   * Serves this filename from the mount root when a requested resource is missing.
   *
   * @param fileName simple filename without path separators or traversal syntax
   * @return options with the SPA fallback filename configured
   * @throws IllegalArgumentException if the filename is unsafe
   */
  public StaticOptions withSpaFallback(String fileName) {
    return new StaticOptions(welcomeFile, Objects.requireNonNull(fileName));
  }

  /**
   * Returns the configured welcome filename.
   *
   * @return welcome filename, or null when disabled
   */
  @Nullable String welcomeFile() {
    return welcomeFile;
  }

  /**
   * Returns the configured SPA fallback filename.
   *
   * @return SPA fallback filename, or null when disabled
   */
  @Nullable String spaFallback() {
    return spaFallback;
  }

  /**
   * Restricts configured resources to simple leaf names inside their selected directory.
   *
   * @param fileName requested filename
   * @return validated filename
   * @throws IllegalArgumentException if the filename could escape or select a directory
   */
  private static String validFileName(String fileName) {
    Objects.requireNonNull(fileName);
    if (fileName.isBlank()
        || ".".equals(fileName)
        || "..".equals(fileName)
        || fileName.indexOf(HttpCharacters.PATH_SEPARATOR) >= 0
        || fileName.indexOf('\\') >= 0
        || fileName.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("Expected a simple static-resource filename");
    }

    return fileName;
  }
}
