package io.github.suppierk.shoostr;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import org.jspecify.annotations.Nullable;

/** Runs operations while holding their supplied lock. */
final class Locks {
  /**
   * Prevents utility instantiation.
   *
   * @throws UnsupportedOperationException always
   */
  private Locks() {
    throw new UnsupportedOperationException("No instance creation is available");
  }

  /**
   * Validates both arguments before acquiring the lock and returns the operation's result.
   *
   * @param <T> result type, which may be nullable
   * @param <E> operation's exception type
   * @param lock lock to acquire without interruptible admission
   * @param supplier operation performed while holding the lock
   * @return operation result, which may be null
   * @throws NullPointerException if either argument is null
   * @throws E if the operation fails
   */
  static <T extends @Nullable Object, E extends Exception> @Nullable T getUnderLock(
      Lock lock, ThrowingSupplier<T, E> supplier) throws E {
    Objects.requireNonNull(lock);
    Objects.requireNonNull(supplier);
    lock.lock();

    try {
      return supplier.get();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Validates both arguments before acquiring the lock and runs the operation.
   *
   * @param <E> operation's exception type
   * @param lock lock to acquire without interruptible admission
   * @param runnable operation performed while holding the lock
   * @throws NullPointerException if either argument is null
   * @throws E if the operation fails
   */
  static <E extends Exception> void runUnderLock(Lock lock, ThrowingRunnable<E> runnable) throws E {
    Objects.requireNonNull(lock);
    Objects.requireNonNull(runnable);
    lock.lock();

    try {
      runnable.run();
    } finally {
      lock.unlock();
    }
  }
}
