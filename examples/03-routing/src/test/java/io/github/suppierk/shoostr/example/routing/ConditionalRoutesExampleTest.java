package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class ConditionalRoutesExampleTest {
    @Test
    void changesAvailabilityWithoutRestartingTheApplication() throws Exception {
        var enabled = new AtomicBoolean(false);
        var preview = previewRequest("true");

        try (var test = TestServer.start(ConditionalRoutesExample.application(enabled::get))) {
            assertEquals(404, test.send(preview).statusCode());

            enabled.set(true);
            var available = test.send(preview, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, available.statusCode());
            assertEquals("preview", available.body());

            enabled.set(false);

            assertEquals(404, test.send(preview).statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {
        false,
        true
    })
    void requiresTheRequestConditionEvenWhenTheFlagIsEnabled(boolean includePreview) throws Exception {
        Consumer<TestRequest> configure = includePreview
            ? previewRequest("false")
            : request -> request.path("/preview/latest");

        try (var test = TestServer.start(ConditionalRoutesExample.application(() -> true))) {
            var response = test.send(configure);

            assertEquals(404, response.statusCode());
            assertEquals("no-store", response.headers()
                .firstValue("Cache-Control")
                .orElseThrow());
        }
    }

    @Test
    void skipsTheRequestConditionWhileTheOuterFlagIsDisabled() throws Exception {
        var enabled = new AtomicBoolean(false);

        try (var test = TestServer.start(ConditionalRoutesExample.application(enabled::get))) {
            // Query decoding fails only if
            // the inner availability condition runs.
            var disabled = test.send(request ->
                request.path("/preview/latest?preview=%FF"));

            assertEquals(404, disabled.statusCode());
            assertEquals("no-store", disabled.headers()
                .firstValue("Cache-Control")
                .orElseThrow());

            enabled.set(true);

            assertEquals(
                400,
                test.send(request -> request
                    .path("/preview/latest?preview=%FF"))
                    .statusCode()
            );
        }
    }

    @Test
    void doesNotRetryTheCatchAllAfterConditionFails() throws Exception {
        try (var test = TestServer.start(ConditionalRoutesExample.application(() -> false))) {
            var unavailable = test.send(previewRequest("true"));

            assertEquals(404, unavailable.statusCode());
            assertEquals("no-store", unavailable.headers()
                .firstValue("Cache-Control")
                .orElseThrow());

            var fallback = test.send(
                request -> request.path("/preview/other"),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, fallback.statusCode());
            assertEquals("fallback", fallback.body());
        }
    }

    @Test
    void retainsMethodDiscoveryWhileTheRouteIsUnavailable() throws Exception {
        var app = ConditionalRoutesExample.application(() -> false);

        try (Closeable appCleanup = app::close) {
            app.routes().when(
                () -> false,
                unavailable -> unavailable.post(
                    "/preview/latest",
                    (request, response) -> response.text("unused")
                )
            );

            try (var test = TestServer.start(app)) {
                var response = test.send(request ->
                    request.path("/preview/latest").method("PUT"));

                assertEquals(405, response.statusCode());
                assertEquals(
                    Set.of("GET", "POST"),
                    Set.of(response.headers()
                        .firstValue("Allow")
                        .orElseThrow()
                        .split(",\\s*"))
                );
            }
        }
    }

    private static Consumer<TestRequest> previewRequest(String value) {
        return request -> request.path("/preview/latest", parts -> parts.queryParam("preview", value));
    }

    @Test
    void rejectsNullFlagBeforeCreatingAnApplication() {
        assertThrows(
            NullPointerException.class,
            () -> ConditionalRoutesExample.application(null)
        );
    }
}
