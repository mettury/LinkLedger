package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlPolicyTest {
    private final UrlPolicy policy = new UrlPolicy();

    @Test
    void preservesMeaningfulQueryFragmentEscapingAndPathCase() {
        assertThat(policy.validate("HTTPS://EXAMPLE.COM:443/Items%2F42?item=42&utm_source=email#Specs"))
                .isEqualTo("https://example.com/Items%2F42?item=42&utm_source=email#Specs");
    }

    @Test
    void acceptsIpv6AndAddsRootPath() {
        assertThat(policy.validate("http://[::1]:8080")).isEqualTo("http://[::1]:8080/");
        assertThat(policy.validate("https://example.com")).isEqualTo("https://example.com/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "//example.com", "https://user:pass@example.com",
            "https://", "https://example.com:99999", "https://example.com:0", "https://example.com:",
            "https://example.com/a b", "https://example.com/\r\nx:injected", " https://example.com", "https://example.com/%zz"})
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
    }
}
