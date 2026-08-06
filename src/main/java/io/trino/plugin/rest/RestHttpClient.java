package io.trino.plugin.rest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RestHttpClient {
    private final RestConfig config;
    private final HttpClient client = HttpClient.newHttpClient();

    public RestHttpClient(RestConfig config) {
        this.config = config;

    }

    /**
     * Returns a live, still-open InputStream over the response body: the caller is responsible
     * for closing it (and, transitively, the underlying connection) once done reading.
     */
    public InputStream fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", String.format("Bearer %s", config.getToken())).GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // best-effort cleanup; the non-200 status is the failure that matters here
                }
                throw new RuntimeException(
                        String.format("Request to url %s failed with status code %d", url, response.statusCode()));
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(String.format("Request to url %s failed", url), e);

        }

    }
}
