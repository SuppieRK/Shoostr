package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class LocksTest {
  @Test
  void returnsTheNullableSupplierResultAndReleasesTheLock() throws IOException {
    var lock = new ReentrantLock();
    ThrowingSupplier<@Nullable String, IOException> supplier = () -> null;

    assertNull(getUnderLock(lock, supplier));
    assertFalse(lock.isLocked());
  }

  @Test
  void propagatesTheCheckedSupplierFailureAndReleasesTheLock() {
    var lock = new ReentrantLock();
    var failure = new IOException("Supplier failed");

    try {
      getUnderLock(
          lock,
          () -> {
            assertTrue(lock.isHeldByCurrentThread());
            throw failure;
          });
      fail("The supplier must propagate its checked failure");
    } catch (IOException actual) {
      assertSame(failure, actual);
    }

    assertFalse(lock.isLocked());
  }

  @Test
  void propagatesTheCheckedFailureAndReleasesTheLock() {
    var lock = new ReentrantLock();
    var failure = new IOException("Operation failed");

    try {
      runUnderLock(
          lock,
          () -> {
            assertTrue(lock.isHeldByCurrentThread());
            throw failure;
          });
      fail("The operation must propagate its checked failure");
    } catch (IOException actual) {
      assertSame(failure, actual);
    }

    assertFalse(lock.isLocked());
  }

  @Test
  void propagatesTheRuntimeFailureAndReleasesTheLock() {
    var lock = new ReentrantLock();
    var failure = new IllegalStateException("Operation failed");

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                runUnderLock(
                    lock,
                    () -> {
                      assertTrue(lock.isHeldByCurrentThread());
                      throw failure;
                    })));

    assertFalse(lock.isLocked());
  }

  @Test
  void propagatesTheErrorAndReleasesTheLock() {
    var lock = new ReentrantLock();
    var failure = new Error("Operation failed");

    assertSame(
        failure,
        assertThrows(
            Error.class,
            () ->
                runUnderLock(
                    lock,
                    () -> {
                      assertTrue(lock.isHeldByCurrentThread());
                      throw failure;
                    })));

    assertFalse(lock.isLocked());
  }
}
