package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadixRoutesLifecycleTest {
  @TempDir Path directory;

  @Test
  void closesEveryMountAndPropagatesTheFirstFailureWithLaterFailuresSuppressed() {
    var firstFailure = new IllegalStateException("first close failure");
    var secondFailure = new IllegalStateException("second close failure");
    var closeOrder = new ArrayList<String>();

    try (var first = failingMount("first", closeOrder, firstFailure);
        var healthy = mount("healthy", closeOrder, () -> {});
        var last = failingMount("last", closeOrder, secondFailure)) {
      var router = RadixRoutes.from(List.of(), List.of(first, healthy, last));
      var reported = assertThrows(RuntimeException.class, router::close);

      assertSame(firstFailure, reported);
      assertArrayEquals(new Throwable[] {secondFailure}, reported.getSuppressed());
      assertEquals(List.of("first", "healthy", "last"), closeOrder);
    }

    assertEquals(List.of("first", "healthy", "last"), closeOrder);
  }

  private StaticFiles failingMount(String name, List<String> closeOrder, RuntimeException failure) {
    return mount(
        name,
        closeOrder,
        () -> {
          throw failure;
        });
  }

  private StaticFiles mount(String name, List<String> closeOrder, Runnable afterClose) {
    return new StaticFiles(
        "/" + name,
        directory,
        StaticOptions.defaults(),
        () -> nativeFactory(name, closeOrder, afterClose));
  }

  private ResourceFactory.Closeable nativeFactory(
      String name, List<String> closeOrder, Runnable afterClose) {
    return new CloseTrackingFactory(name, closeOrder, afterClose);
  }

  @Test
  @SuppressWarnings("NullAway")
  void validatesMountAndOptionsBeforeFactoryAcquisition() {
    assertThrows(
        NullPointerException.class,
        () ->
            new StaticFiles(
                null,
                directory,
                StaticOptions.defaults(),
                () -> fail("Factory acquired before mount validation")));
    assertThrows(
        NullPointerException.class,
        () ->
            new StaticFiles(
                "/files",
                directory,
                null,
                () -> fail("Factory acquired before options validation")));
  }

  @Test
  void closesTheAcquiredFactoryAndSuppressesCleanupFailureWhenResolutionFails() {
    var cleanupFailure = new IllegalStateException("cleanup failure");
    var closeOrder = new ArrayList<String>();
    var acquisitions = new AtomicInteger();
    var failure = mountResolutionFailure(acquisitions, closeOrder, cleanupFailure);

    assertEquals(1, acquisitions.get());
    assertEquals(List.of("failed"), closeOrder);
    assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
  }

  private IllegalArgumentException mountResolutionFailure(
      AtomicInteger acquisitions, List<String> closeOrder, RuntimeException cleanupFailure) {
    return assertThrows(
        IllegalArgumentException.class,
        () ->
            new StaticFiles(
                "/files",
                directory.resolve("missing"),
                StaticOptions.defaults(),
                () -> {
                  acquisitions.incrementAndGet();
                  return nativeFactory(
                      "failed",
                      closeOrder,
                      () -> {
                        throw cleanupFailure;
                      });
                }));
  }

  private static final class CloseTrackingFactory implements ResourceFactory.Closeable {
    private final String name;
    private final List<String> closeOrder;
    private final Runnable afterClose;
    private final ResourceFactory.Closeable delegate;
    private boolean closed;

    private CloseTrackingFactory(String name, List<String> closeOrder, Runnable afterClose) {
      this.name = Objects.requireNonNull(name);
      this.closeOrder = Objects.requireNonNull(closeOrder);
      this.afterClose = Objects.requireNonNull(afterClose);
      this.delegate = ResourceFactory.closeable();
      this.closed = false;
    }

    @Override
    public Resource newResource(URI uri) {
      return delegate.newResource(uri);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }

      closed = true;
      delegate.close();
      closeOrder.add(name);
      afterClose.run();
    }
  }
}
