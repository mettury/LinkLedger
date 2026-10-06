package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import dev.linkledger.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LinkService {
    private static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59.999999Z");
    private final LinkRepository repository;
    private final UrlPolicy urlPolicy;
    private final CodeGenerator codes;
    private final AppProperties properties;
    private final Clock clock;
    private final AliasPolicy aliases;
    private final TransactionTemplate transaction;

    public LinkService(LinkRepository repository, UrlPolicy urlPolicy, CodeGenerator codes,
                       AppProperties properties, Clock clock, AliasPolicy aliases, PlatformTransactionManager manager) {
        this.repository = repository;
        this.urlPolicy = urlPolicy;
        this.codes = codes;
        this.properties = properties;
        this.clock = clock;
        this.aliases = aliases;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
    }

    public CreateResult create(CreateLinkRequest request, String idempotencyKey) {
        String destination = urlPolicy.validate(request.url());
        if (request.expiresAt() != null && request.expiresAt().isAfter(MAX_EXPIRY)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expiresAt must be no later than 9999-12-31T23:59:59.999999Z");
        }
        aliases.validate(request.customAlias());
        String keyHash = validateKey(idempotencyKey);
        String fingerprint = sha256(part(destination) + part(request.customAlias()) + part(request.expiresAt()));
        if (keyHash != null) {
            CreateResult replay = replay(keyHash, fingerprint);
            if (replay != null) {
                return replay;
            }
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiry = request.expiresAt() == null ? null : request.expiresAt().truncatedTo(ChronoUnit.MICROS);
        if (expiry != null && !expiry.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = request.customAlias() == null ? codes.generate() : request.customAlias();
            if (aliases.isReserved(candidate)) {
                continue;
            }
            Link link = new Link(candidate, destination, now, expiry, false);
            try {
                transaction.executeWithoutResult(status -> {
                    repository.insert(link);
                    if (keyHash != null) {
                        repository.insertIdempotency(keyHash, fingerprint, link);
                    }
                });
                return new CreateResult(response(link), true);
            } catch (DataIntegrityViolationException conflict) {
                // Each failed insert has its own rolled-back transaction. Never reuse an aborted PostgreSQL transaction.
                if (keyHash != null) {
                    CreateResult replay = replay(keyHash, fingerprint);
                    if (replay != null) {
                        return replay;
                    }
                }
                if (repository.find(link.code()).isEmpty()) {
                    throw conflict;
                }
                if (request.customAlias() != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "Custom alias is already used; disabled and expired codes are not reused");
                }
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a code; please retry");
    }

    public Link find(String code) {
        validateCode(code);
        return repository.find(code).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Short link not found"));
    }

    public Link resolve(String code) {
        Link link = find(code);
        if (!link.status(clock.instant()).equals("ACTIVE")) {
            throw new ApiException(HttpStatus.GONE, "Short link is expired or disabled");
        }
        return link;
    }

    public void disable(String code) {
        validateCode(code);
        if (!repository.disable(code)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Short link not found");
        }
    }

    public LinkResponse response(Link link) {
        return new LinkResponse(link.code(), properties.baseUrl() + "/" + link.code(), link.url(),
                link.createdAt(), link.expiresAt(), link.status(clock.instant()));
    }

    private CreateResult replay(String keyHash, String fingerprint) {
        return repository.findIdempotency(keyHash).map(record -> {
            if (!MessageDigest.isEqual(record.requestHash().getBytes(StandardCharsets.UTF_8),
                    fingerprint.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request");
            }
            return new CreateResult(response(find(record.code())), false);
        }).orElse(null);
    }

    private String validateKey(String key) {
        if (key == null) {
            return null;
        }
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1-128 letters, digits, periods, underscores, colons or hyphens");
        }
        return sha256(key);
    }

    private void validateCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{3,32}")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Short link not found");
        }
    }

    private String part(Object value) {
        String text = Objects.toString(value, "");
        return text.length() + ":" + text;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record CreateResult(LinkResponse link, boolean created) {
    }
}
