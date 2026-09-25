package io.github.suppierk.shoostr.http.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpExceptionTest {
  @ParameterizedTest
  @MethodSource("errors")
  void exposesFixedStatusAndCorrectCatchFamily(
      HttpStatusCodes status, Class<? extends HttpException> type)
      throws ReflectiveOperationException {
    var exception = type.getConstructor().newInstance();
    assertSame(status, exception.statusCode());
    assertEquals(status.reasonPhrase(), exception.getMessage());
    assertNull(exception.getCause());
    assertInstanceOf(RuntimeException.class, exception);
    assertSame(
        status.isClientError() ? HttpClientException.class : HttpServerException.class,
        type.getSuperclass());
    assertTrue(exception.getStackTrace().length > 0);
    var suppressed = new IllegalStateException("cleanup failure");
    exception.addSuppressed(suppressed);
    assertSame(suppressed, exception.getSuppressed()[0]);
  }

  @ParameterizedTest
  @MethodSource("errors")
  void preservesDiagnosticsWithoutUsingTheCauseAsTheDefaultMessage(
      HttpStatusCodes status, Class<? extends HttpException> type)
      throws ReflectiveOperationException {
    var cause = new IllegalArgumentException("internal details");
    var withMessage = type.getConstructor(String.class).newInstance("diagnostic message");
    assertSame(status, withMessage.statusCode());
    assertEquals("diagnostic message", withMessage.getMessage());
    assertNull(withMessage.getCause());
    var withCause = type.getConstructor(Throwable.class).newInstance(cause);
    assertSame(status, withCause.statusCode());
    assertEquals(status.reasonPhrase(), withCause.getMessage());
    assertSame(cause, withCause.getCause());
    var withBoth =
        type.getConstructor(String.class, Throwable.class).newInstance("diagnostic message", cause);
    assertSame(status, withBoth.statusCode());
    assertEquals("diagnostic message", withBoth.getMessage());
    assertSame(cause, withBoth.getCause());
    var withNullMessage =
        type.getConstructor(String.class, Throwable.class).newInstance(null, cause);
    assertEquals(status.reasonPhrase(), withNullMessage.getMessage());
    assertSame(cause, withNullMessage.getCause());
    var withEmptyMessage = type.getConstructor(String.class).newInstance("");
    assertEquals("", withEmptyMessage.getMessage());
  }

  @ParameterizedTest
  @MethodSource("errors")
  void retainsStatusAndDiagnosticsThroughJavaSerialization(
      HttpStatusCodes status, Class<? extends HttpException> type) throws Exception {
    var exception =
        type.getConstructor(String.class, Throwable.class)
            .newInstance("diagnostic message", new IllegalStateException("cause"));
    var bytes = new ByteArrayOutputStream();

    try (var output = new ObjectOutputStream(bytes)) {
      output.writeObject(exception);
    }

    try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      var restored = assertInstanceOf(HttpException.class, input.readObject());
      assertSame(type, restored.getClass());
      assertSame(status, restored.statusCode());
      assertEquals("diagnostic message", restored.getMessage());
      var cause = assertInstanceOf(IllegalStateException.class, restored.getCause());
      assertEquals("cause", cause.getMessage());
    }
  }

  @Test
  void coversEveryErrorStatusExactlyOnce() {
    var expected =
        Arrays.stream(HttpStatusCodes.values())
            .filter(HttpStatusCodes::isError)
            .collect(Collectors.toSet());
    var actual = errors().map(arguments -> (HttpStatusCodes) arguments.get()[0]).toList();
    assertEquals(expected.size(), actual.size());
    assertEquals(expected, actual.stream().collect(Collectors.toSet()));
  }

  @Test
  void supportsSpecificFamilyAndRootCatches() {
    var missing = new NotFoundException();

    try {
      throw missing;
    } catch (NotFoundException caught) {
      assertSame(missing, caught);
    }

    try {
      throw missing;
    } catch (HttpClientException caught) {
      assertSame(missing, caught);
    }

    var unavailable = new ServiceUnavailableException();

    try {
      throw unavailable;
    } catch (HttpClientException caught) {
      fail("A server error must not match the client error family", caught);
    } catch (HttpServerException caught) {
      assertSame(unavailable, caught);
    }

    for (HttpException exception : new HttpException[] {missing, unavailable}) {
      try {
        throw exception;
      } catch (HttpException caught) {
        assertSame(exception, caught);
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsMissingNonErrorAndWrongFamilyStatuses() {
    assertThrows(NullPointerException.class, () -> new HttpException(null, null, null) {});
    assertThrows(NullPointerException.class, () -> new HttpException(null, "message", null) {});
    assertThrows(
        IllegalArgumentException.class, () -> new HttpException(HttpStatusCodes.OK, null, null) {});
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpClientException(HttpStatusCodes.INTERNAL_SERVER_ERROR, null, null) {});
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpServerException(HttpStatusCodes.BAD_REQUEST, null, null) {});
  }

  @Test
  @SuppressWarnings("deprecation")
  void obsoleteExceptionIsDeprecated() {
    assertTrue(NotExtendedException.class.isAnnotationPresent(Deprecated.class));
  }

  @SuppressWarnings("deprecation")
  private static Stream<Arguments> errors() {
    return Stream.of(
        Arguments.of(HttpStatusCodes.BAD_REQUEST, BadRequestException.class),
        Arguments.of(HttpStatusCodes.UNAUTHORIZED, UnauthorizedException.class),
        Arguments.of(HttpStatusCodes.PAYMENT_REQUIRED, PaymentRequiredException.class),
        Arguments.of(HttpStatusCodes.FORBIDDEN, ForbiddenException.class),
        Arguments.of(HttpStatusCodes.NOT_FOUND, NotFoundException.class),
        Arguments.of(HttpStatusCodes.METHOD_NOT_ALLOWED, MethodNotAllowedException.class),
        Arguments.of(HttpStatusCodes.NOT_ACCEPTABLE, NotAcceptableException.class),
        Arguments.of(
            HttpStatusCodes.PROXY_AUTHENTICATION_REQUIRED,
            ProxyAuthenticationRequiredException.class),
        Arguments.of(HttpStatusCodes.REQUEST_TIMEOUT, RequestTimeoutException.class),
        Arguments.of(HttpStatusCodes.CONFLICT, ConflictException.class),
        Arguments.of(HttpStatusCodes.GONE, GoneException.class),
        Arguments.of(HttpStatusCodes.LENGTH_REQUIRED, LengthRequiredException.class),
        Arguments.of(HttpStatusCodes.PRECONDITION_FAILED, PreconditionFailedException.class),
        Arguments.of(HttpStatusCodes.CONTENT_TOO_LARGE, ContentTooLargeException.class),
        Arguments.of(HttpStatusCodes.URI_TOO_LONG, UriTooLongException.class),
        Arguments.of(HttpStatusCodes.UNSUPPORTED_MEDIA_TYPE, UnsupportedMediaTypeException.class),
        Arguments.of(HttpStatusCodes.RANGE_NOT_SATISFIABLE, RangeNotSatisfiableException.class),
        Arguments.of(HttpStatusCodes.EXPECTATION_FAILED, ExpectationFailedException.class),
        Arguments.of(HttpStatusCodes.MISDIRECTED_REQUEST, MisdirectedRequestException.class),
        Arguments.of(HttpStatusCodes.UNPROCESSABLE_CONTENT, UnprocessableContentException.class),
        Arguments.of(HttpStatusCodes.LOCKED, LockedException.class),
        Arguments.of(HttpStatusCodes.FAILED_DEPENDENCY, FailedDependencyException.class),
        Arguments.of(HttpStatusCodes.TOO_EARLY, TooEarlyException.class),
        Arguments.of(HttpStatusCodes.UPGRADE_REQUIRED, UpgradeRequiredException.class),
        Arguments.of(HttpStatusCodes.PRECONDITION_REQUIRED, PreconditionRequiredException.class),
        Arguments.of(HttpStatusCodes.TOO_MANY_REQUESTS, TooManyRequestsException.class),
        Arguments.of(
            HttpStatusCodes.REQUEST_HEADER_FIELDS_TOO_LARGE,
            RequestHeaderFieldsTooLargeException.class),
        Arguments.of(
            HttpStatusCodes.UNAVAILABLE_FOR_LEGAL_REASONS,
            UnavailableForLegalReasonsException.class),
        Arguments.of(HttpStatusCodes.INTERNAL_SERVER_ERROR, InternalServerErrorException.class),
        Arguments.of(HttpStatusCodes.NOT_IMPLEMENTED, NotImplementedException.class),
        Arguments.of(HttpStatusCodes.BAD_GATEWAY, BadGatewayException.class),
        Arguments.of(HttpStatusCodes.SERVICE_UNAVAILABLE, ServiceUnavailableException.class),
        Arguments.of(HttpStatusCodes.GATEWAY_TIMEOUT, GatewayTimeoutException.class),
        Arguments.of(
            HttpStatusCodes.HTTP_VERSION_NOT_SUPPORTED, HttpVersionNotSupportedException.class),
        Arguments.of(HttpStatusCodes.VARIANT_ALSO_NEGOTIATES, VariantAlsoNegotiatesException.class),
        Arguments.of(HttpStatusCodes.INSUFFICIENT_STORAGE, InsufficientStorageException.class),
        Arguments.of(HttpStatusCodes.LOOP_DETECTED, LoopDetectedException.class),
        Arguments.of(HttpStatusCodes.NOT_EXTENDED, NotExtendedException.class),
        Arguments.of(
            HttpStatusCodes.NETWORK_AUTHENTICATION_REQUIRED,
            NetworkAuthenticationRequiredException.class));
  }
}
