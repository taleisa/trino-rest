package io.trino.plugin.rest;

import java.io.IOException;
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

    public String fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", String.format("Bearer %s", config.getToken())).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        String.format("Request to url %s failed with status code %d", url, response.statusCode()));
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(String.format("Request to url %s failed", url), e);

        }

    }
}
