package dev.linkledger.api;

import dev.linkledger.analytics.AnalyticsResponse;
import dev.linkledger.analytics.AnalyticsService;
import dev.linkledger.links.CreateLinkRequest;
import dev.linkledger.links.Link;
import dev.linkledger.links.LinkResponse;
import dev.linkledger.links.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LinkController {
    private final LinkService links;
    private final AnalyticsService analytics;
    private final Clock clock;

    public LinkController(LinkService links, AnalyticsService analytics, Clock clock) {
        this.links = links;
        this.analytics = analytics;
        this.clock = clock;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        LinkService.CreateResult result = links.create(request, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header("Idempotency-Replayed", Boolean.toString(!result.created()))
                .location(URI.create("/api/v1/urls/" + result.link().code())).cacheControl(CacheControl.noStore())
                .body(result.link());
    }

    @RequestMapping(value = "/{code:[A-Za-z0-9_-]{3,32}}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        Link link = links.resolve(code);
        if (request.getMethod().equals("GET")) {
            analytics.record(code, clock.instant());
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.url()))
                .cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/v1/urls/{code}")
    public LinkResponse details(@PathVariable String code) {
        return links.response(links.find(code));
    }

    @GetMapping("/api/v1/urls/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) {
        links.find(code);
        return analytics.get(code);
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> disable(@PathVariable String code) {
        links.disable(code);
        return ResponseEntity.noContent().build();
    }
}
