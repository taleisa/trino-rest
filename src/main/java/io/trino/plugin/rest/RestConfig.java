package io.trino.plugin.rest;

import java.util.Map;

public class RestConfig {
    private final String token;
    private final String specUrl;
    private final String specPath;
    // For different envs or if routing through proxy
    private final String baseUrl;

    public RestConfig(Map<String, String> config) {
        this.token = config.get("rest.token");
        this.specUrl = config.get("rest.specUrl");
        this.specPath = config.get("rest.specPath");
        this.baseUrl = config.get("rest.baseUrl");
        // Exactly one of `specUrl` and `specPath`
        if (!(this.specUrl == null ^ this.specPath == null)) {
            throw new IllegalArgumentException("Exactly one of specUrl and specPath must be set");
        }
        if (this.baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must be set");
        }
    }

    public String getToken() {
        return token;
    }

    public String getSpecUrl() {
        return specUrl;
    }

    public String getSpecPath() {
        return specPath;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
