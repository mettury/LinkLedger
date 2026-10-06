package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Structural validation only: never resolves DNS or fetches the destination. */
@Component
public class UrlPolicy {
    public String validate(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw invalid();
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() < -1 || uri.getPort() > 65535
                    || uri.getPort() == 0 || uri.getRawAuthority().endsWith(":")) {
                throw invalid();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = host + ((port == -1 || scheme.equals("http") && port == 80
                    || scheme.equals("https") && port == 443) ? "" : ":" + port);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String target = scheme + "://" + authority + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            target = URI.create(target).toASCIIString();
            if (target.length() > 4096) {
                throw invalid();
            }
            return target;
        } catch (URISyntaxException ex) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST,
                "url must be an absolute HTTP(S) URL with a valid host, no credentials or whitespace, and at most 4096 characters");
    }
}
