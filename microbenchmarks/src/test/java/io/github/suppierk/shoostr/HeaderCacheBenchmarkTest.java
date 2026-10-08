package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeaderCacheBenchmarkTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 128, 256, 512, 1024})
  void deliversAllHeadersAndCompletesEveryRequestRegardlessOfCacheCapacity(int capacity) {
    for (var headers :
        new String[] {
          "repeated",
          "varying",
          "large",
          "cookie64",
          "cookie192",
          "cookie384",
          "cookie768",
          "cookie1536"
        }) {
      var fixture = new HeaderCacheBenchmark();
      fixture.capacity = capacity;
      fixture.headers = headers;
      fixture.setup();
      for (int request = 0; request < 128; request++) {
        int expected = headers.startsWith("cookie") ? 8 : 7;
        assertEquals(expected, fixture.reusedConnection());
        assertEquals(expected, fixture.newConnection());
      }
    }
  }
}
