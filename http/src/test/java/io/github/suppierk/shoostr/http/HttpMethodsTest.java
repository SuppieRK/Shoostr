package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpMethodsTest {
  @ParameterizedTest
  @EnumSource(HttpMethods.class)
  void roundTripsWireNames(HttpMethods method) {
    assertEquals(method, HttpMethods.httpMethod(method.value()).orElseThrow());
    assertEquals(method.name().replace('_', '-'), method.value());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"get", "Get", " GET", "GET ", "CUSTOM", "*", "BASELINE_CONTROL"})
  void rejectsUnknownTokensWithoutNormalizationOrExceptions(String value) {
    assertTrue(HttpMethods.httpMethod(value).isEmpty());
  }
}
