package com.cdq.countries.country;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CountriesRestClientHttpStubTest {

    private HttpServer server;
    private final AtomicReference<StubResponse> response = new AtomicReference<>();
    private final AtomicReference<HttpExchange> request = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(exchange);
            StubResponse stubResponse = response.get();
            if (!stubResponse.delay().isZero()) {
                try {
                    Thread.sleep(stubResponse.delay());
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = stubResponse.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubResponse.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void mapsTheV5EnvelopeAndMovesThePrimaryCapitalToTheFront() {
        response.set(new StubResponse(200, """
                {"data":{"objects":[{
                  "names":{"common":"Poland","official":"Republic of Poland"},
                  "codes":{"alpha_2":"PL","alpha_3":"POL"},
                  "capitals":[
                    {"name":"Krakow","attributes":{"primary":false}},
                    {"name":"Warsaw","attributes":{"primary":true}}
                  ],
                  "region":"Europe","subregion":"Central Europe","population":38000000,
                  "currencies":[
                    {"code":"PLN","name":"Polish zloty","symbol":"zł"},
                    {"code":"EUR","name":"Euro","symbol":"€"}
                  ],
                  "languages":[
                    {"iso_639_3":"pol","bcp47":"pl","name":"Polish","native_name":"polski"},
                    {"iso_639_3":"eng","bcp47":"en","name":"English","native_name":"English"}
                  ]
                }]},"notice":{"message":"demo"}}
                """));

        CountryLookupResult result = client().byCommonName("Poland");

        assertThat(result.error()).isNull();
        assertThat(result.country()).isEqualTo(new CountryInfo(
                "Poland", "Republic of Poland", "PL", "POL", "Europe", "Central Europe", 38_000_000L,
                java.util.List.of("Warsaw", "Krakow"),
                java.util.Map.of("PLN", "Polish zloty", "EUR", "Euro"),
                java.util.Map.of("pl", "Polish", "en", "English")));
        assertThat(request.get().getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(request.get().getRequestURI().getPath()).isEqualTo("/names.common/Poland");
        assertThat(request.get().getRequestURI().getQuery()).contains("response_fields=names.common,names.official,codes.alpha_2,codes.alpha_3,capitals,region,subregion,population,currencies,languages");
    }

    @Test
    void mapsMissingOptionalFieldsToSafeEmptyValues() {
        response.set(new StubResponse(200, "{" + "\"data\":{\"objects\":[{\"names\":{\"common\":\"Nauru\"}}]}" + "}"));

        CountryLookupResult result = client().byCapital("Yaren");

        assertThat(result.error()).isNull();
        assertThat(result.country()).isEqualTo(new CountryInfo(
                "Nauru", "", "", "", "", "", null,
                java.util.List.of(), java.util.Map.of(), java.util.Map.of()));
        assertThat(request.get().getRequestURI().getPath()).isEqualTo("/capitals/Yaren");
    }

    @Test
    void mapsAnEmptyObjectsArrayToANotFoundErrorWithoutReturningTheResponseBody() {
        response.set(new StubResponse(200, "{\"data\":{\"objects\":[]},\"internal\":\"raw-secret-body\"}"));

        CountryLookupResult result = client().byCommonName("Missing");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(CountryLookupErrorCode.NOT_FOUND,
                "No matching country was found."));
        assertThat(result.error().message()).doesNotContain("raw-secret-body");
    }

    @ParameterizedTest
    @CsvSource({
            "404,NOT_FOUND,No matching country was found.",
            "401,AUTHENTICATION,The countries service authentication failed.",
            "403,AUTHENTICATION,The countries service authentication failed.",
            "429,RATE_LIMITED,The countries service rate limit was reached.",
            "500,UNAVAILABLE,The countries service is unavailable."
    })
    void mapsHttpFailuresToSafeDomainErrors(int status, CountryLookupErrorCode code, String message) {
        response.set(new StubResponse(status, "{\"detail\":\"raw-secret-body\"}"));

        CountryLookupResult result = client().byCommonName("Poland");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(code, message));
        assertThat(result.error().message()).doesNotContain("raw-secret-body");
        assertThat(result.error().message()).doesNotContain("test-api-key");
    }

    @Test
    void mapsMalformedJsonToASafeMalformedResponseError() {
        response.set(new StubResponse(200, "{not-json}"));

        CountryLookupResult result = client().byCommonName("Poland");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(CountryLookupErrorCode.MALFORMED_RESPONSE,
                "The countries service returned an invalid response."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullElementsInV5Arrays")
    void mapsNullArrayElementsToASafeMalformedResponse(String scenario, String body) {
        response.set(new StubResponse(200, body));

        CountryLookupResult result = client().byCommonName("Poland");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(CountryLookupErrorCode.MALFORMED_RESPONSE,
                "The countries service returned an invalid response."));
        assertThat(result.error().message()).doesNotContain("raw-secret-body").doesNotContain("test-api-key");
    }

    @Test
    void mapsAReadTimeoutToASafeTimeoutError() {
        response.set(new StubResponse(200, "{\"data\":{\"objects\":[]}}", Duration.ofMillis(300)));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofMillis(50));
        CountriesRestClient timedClient = client(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer test-api-key")
                .build());

        CountryLookupResult result = timedClient.byCommonName("Poland");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(CountryLookupErrorCode.TIMEOUT,
                "The countries service timed out."));
    }

    @Test
    void mapsAnUnreachableUpstreamToASafeUnavailableError() {
        CountryLookupResult result = client(RestClient.builder().baseUrl("http://127.0.0.1:1")
                .defaultHeader("Authorization", "Bearer test-api-key").build()).byCommonName("Poland");

        assertThat(result.country()).isNull();
        assertThat(result.error()).isEqualTo(new CountryLookupError(CountryLookupErrorCode.UNAVAILABLE,
                "The countries service is unavailable."));
    }

    private CountriesRestClient client() {
        return client(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .defaultHeader("Authorization", "Bearer test-api-key")
                .build());
    }

    private CountriesRestClient client(RestClient restClient) {
        return new CountriesRestClient(restClient, new ObjectMapper());
    }

    private static Stream<Arguments> nullElementsInV5Arrays() {
        return Stream.of(
                Arguments.of("data.objects", "{\"data\":{\"objects\":[null]},\"internal\":\"raw-secret-body\"}"),
                Arguments.of("capitals", countryWith("\"capitals\":[null]")),
                Arguments.of("currencies", countryWith("\"currencies\":[null]")),
                Arguments.of("languages", countryWith("\"languages\":[null]")));
    }

    private static String countryWith(String malformedField) {
        return "{\"data\":{\"objects\":[{\"names\":{\"common\":\"Poland\"}," + malformedField
                + "}]},\"internal\":\"raw-secret-body\"}";
    }

    private record StubResponse(int status, String body, Duration delay) {
        private StubResponse(int status, String body) {
            this(status, body, Duration.ZERO);
        }
    }
}
