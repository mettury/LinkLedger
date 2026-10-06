package dev.linkledger.links;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateLinkRequest(@NotBlank @Size(max = 4096) String url,
                                @Size(max = 32) String customAlias, Instant expiresAt) {
}
