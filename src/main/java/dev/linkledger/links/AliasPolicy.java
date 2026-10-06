package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AliasPolicy {
    private static final Set<String> RESERVED = Set.of("api", "actuator", "error", "assets", "static",
            "docs", "index", "favicon", "robots", "admin", "login");

    public void validate(String alias) {
        if (alias != null && (!alias.matches("[A-Za-z0-9_-]{3,32}") || isReserved(alias))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "customAlias must contain 3-32 letters, digits, underscores or hyphens and cannot be a reserved route");
        }
    }

    public boolean isReserved(String code) {
        return RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }
}
