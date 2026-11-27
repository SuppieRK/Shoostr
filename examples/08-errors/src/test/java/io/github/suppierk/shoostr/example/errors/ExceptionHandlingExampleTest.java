package io.github.suppierk.shoostr.example.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.suppierk.shoostr.testing.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(15)
class ExceptionHandlingExampleTest {
    @Test
    void fallsBackSafelyWhenScopedRendererFailsWithoutTryingTheApplicationRenderer() throws Exception {
        var app = ExceptionHandlingExample.application();

        try (Closeable appCleanup = app::close) {
            app.routes().path("/renderer-failure",
                group -> {
                    group.exception(
                        IllegalStateException.class,
                        (exception, request, response) -> {
                            response
                                .setHeader("X-Renderer-Diagnostic", "private")
                                .text("Private renderer output");
                            throw new IOException("Private renderer diagnostics");
                        });

                    group.get((request, response) -> {
                        throw new IllegalStateException("Private original diagnostics");
                    });
            });

            try (var test = TestServer.start(app)) {
                var response = test.send(request -> request
                    .path("/renderer-failure"),
                    HttpResponse.BodyHandlers.ofString());

                assertEquals(500, response.statusCode());
                assertEquals("Internal Server Error", response.body());
                assertTrue(response.headers()
                    .firstValue("X-Renderer-Diagnostic")
                    .isEmpty());
            }
        }
    }

    @Test
    void abortsCommittedOutputWithoutInvokingAnErrorRenderer() throws Exception {
        var release = new CountDownLatch(1);
        var rendererCalls = new AtomicInteger();
        var app = ExceptionHandlingExample.application();

        try (Closeable appCleanup = app::close) {
            app.routes().exception(
                IOException.class,
                (exception, request, response) -> {
                    rendererCalls.incrementAndGet();
                    response.text("Must not run after commitment");
                });

            app.routes().get("/committed", (request, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("visible");
                stream.flush();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Reader did not release the handler");
                }

                stream.write("Private buffered output");
                throw new IOException("Private transport diagnostics");
            });

            try (var test = TestServer.start(app)) {
                try {
                    var response = test.send(request -> request
                        .path("/committed")
                        .timeout(Duration.ofSeconds(5)),
                        HttpResponse.BodyHandlers.ofInputStream());

                    assertEquals(200, response.statusCode());

                    try (var input = response.body()) {
                        assertEquals(
                            "visible",
                            new String(
                                input.readNBytes(7),
                                StandardCharsets.UTF_8
                            )
                        );

                        release.countDown();
                        var tail = new ByteArrayOutputStream();

                        assertThrows(
                            IOException.class,
                            () -> {
                                int value;
                                while ((value = input.read()) != -1) {
                                    tail.write(value);
                                }
                            });
                        assertEquals(
                            "",
                            tail.toString(StandardCharsets.UTF_8)
                        );
                    }

                    assertEquals(0, rendererCalls.get());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "/recover, value, bad, 400, Use an integer",
        "/recover, value, 42, 200, 42",
        "/number, value, 42, 200, 42",
        "/input, name, Ada, 200, Ada"
    })
    void recoversLocallyOrReturnsValidInput(
        String path,
        String queryName,
        String queryValue,
        int status,
        String body
    ) throws Exception {
        try (var test = TestServer.start(ExceptionHandlingExample.application())) {
            var response = test.send(request -> request
                .path(path, parts -> parts
                    .queryParam(queryName, queryValue)),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(status, response.statusCode());
            assertEquals(body, response.body());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "/builtin, 400, Bad Request",
        "/unhandled, 500, Internal Server Error",
        "/cause, 500, Internal Server Error"
    })
    void usesSafeDefaultsWithoutUnwrappingTheCause(
        String path,
        int status,
        String body
    ) throws Exception {
        try (var test = TestServer.start(ExceptionHandlingExample.application())) {
            var response = test.send(request -> request.path(path),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(status, response.statusCode());
            assertEquals(body, response.body());
            assertEquals(
                "text/plain; charset=utf-8",
                response.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "/input, , 400, Invalid input",
        "/number, nope, 422, Integer required",
        "/group/input, , 409, Group input rejected",
        "/group/number, nope, 422, Integer required",
        "/inherited/input, , 400, Invalid input"
    })
    void selectsSpecificInheritedAndOverriddenScopeRenderers(
        String path,
        String value,
        int status,
        String body
    ) throws Exception {
        try (var test = TestServer.start(ExceptionHandlingExample.application())) {
            var response = requestWithOptionalValue(test, path, value);

            assertEquals(status, response.statusCode());
            assertEquals(body, response.body());
        }
    }

    private static HttpResponse<String> requestWithOptionalValue(TestServer test, String path, String value)
        throws IOException, InterruptedException {
        return test.send(request -> request.path(path, parts -> {
            if (value != null) {
                parts.queryParam("value", value);
            }
        }), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rendersAnApplicationFailureWithoutPrivateStagedOutput() throws Exception {
        try (var test = TestServer.start(ExceptionHandlingExample.application())) {
            var response = test.send(request -> request.path("/checkout"),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertEquals("Request failed", response.body());
            assertEquals(
                "text/plain; charset=utf-8",
                response.headers()
                    .firstValue("Content-Type")
                    .orElseThrow());
            assertTrue(response.headers()
                .firstValue("X-Diagnostic")
                .isEmpty());
        }
    }
}
