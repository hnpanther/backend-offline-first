package com.hnp.backendofflinefirst.service.alarm;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where an alarm webhook may point (V21). Names are resolved through a table here, so the rules
 * are tested without DNS — including a name that resolves to an internal address, which is the
 * attack the address check exists for.
 */
class AlarmWebhookTargetPolicyTest {

    private static final String FORBIDDEN = "Webhook host resolves to a loopback, link-local or unspecified address.";
    private static final String NOT_LISTED = "Webhook host is not in app.alarm.webhook.allowed-hosts.";

    private static final Map<String, String> DNS = Map.of(
            "sms.plant.local", "10.20.0.15",
            "hooks.example.com", "203.0.113.10",
            "rebind.example.com", "127.0.0.1",
            "metadata.example.com", "169.254.169.254",
            "localhost", "127.0.0.1");

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        String address = DNS.get(host);
        if (address != null) {
            return new InetAddress[]{InetAddress.getByName(address)};
        }
        // A literal address resolves to itself, as the JDK's resolver does, without a lookup.
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return new InetAddress[]{InetAddress.getByName(host)};
        }
        throw new UnknownHostException(host);
    }

    private static AlarmWebhookTargetPolicy policy(String allowedHosts) {
        return new AlarmWebhookTargetPolicy(allowedHosts, AlarmWebhookTargetPolicyTest::resolve);
    }

    @Test
    void aPrivatePlantAddressIsAllowedBecauseThatIsWhereReceiversLive() {
        assertThat(policy("").validate("https://sms.plant.local/hooks/alarm").getHost()).isEqualTo("sms.plant.local");
        assertThat(policy("").validate("http://192.168.1.40:8080/alarm")).isNotNull();
    }

    @Test
    void loopbackLinkLocalAndUnspecifiedAreNeverAReceiver() {
        AlarmWebhookTargetPolicy open = policy("");
        for (String url : new String[]{"http://localhost/x", "http://127.0.0.1:5432/", "http://[::1]/x",
                "http://169.254.169.254/latest/meta-data", "http://0.0.0.0/", "http://[fe80::1]/x"}) {
            assertThatThrownBy(() -> open.validate(url)).as(url).hasMessage(FORBIDDEN);
        }
    }

    @Test
    void aNameThatResolvesToAForbiddenAddressIsRefusedLikeTheAddressItself() {
        assertThatThrownBy(() -> policy("").validate("https://rebind.example.com/hook")).hasMessage(FORBIDDEN);
        assertThatThrownBy(() -> policy("").validate("https://metadata.example.com/hook")).hasMessage(FORBIDDEN);
    }

    @Test
    void theAllowListIsTheCompleteListOfHostsAndAcceptsWildcards() {
        AlarmWebhookTargetPolicy bounded = policy("hooks.example.com, *.plant.local");
        assertThat(bounded.validate("https://hooks.example.com/a")).isNotNull();
        assertThat(bounded.validate("https://sms.plant.local/a")).isNotNull();
        assertThatThrownBy(() -> bounded.validate("https://evil.example.org/a")).hasMessage(NOT_LISTED);
        assertThatThrownBy(() -> bounded.validate("https://plant.local/a"))
                .as("*.plant.local names sub-domains, not the bare domain")
                .hasMessage(NOT_LISTED);
    }

    @Test
    void aWildcardNeverOpensLoopbackButAnExactEntryDeliberatelyDoes() {
        assertThatThrownBy(() -> policy("*.example.com").validate("https://rebind.example.com/hook"))
                .hasMessage(FORBIDDEN);
        assertThat(policy("127.0.0.1").validate("http://127.0.0.1:9000/hook")).isNotNull();
    }

    @Test
    void theAddressMustBeAbsoluteHttpWithNoCredentialsInIt() {
        AlarmWebhookTargetPolicy open = policy("");
        assertThatThrownBy(() -> open.validate("ftp://hooks.example.com/x"))
                .hasMessage("Webhook URL must be an absolute http(s) URL.");
        assertThatThrownBy(() -> open.validate("/relative/path"))
                .hasMessage("Webhook URL must be an absolute http(s) URL.");
        assertThatThrownBy(() -> open.validate("https://user:secret@hooks.example.com/x"))
                .hasMessage("Webhook URL must not contain a user name or password.");
        assertThatThrownBy(() -> open.validate("  ")).hasMessage("Webhook URL is required.");
        assertThatThrownBy(() -> open.validate("https://unknown.invalid/x"))
                .hasMessage("Webhook host cannot be resolved.");
    }

    @Test
    void theCheckBeforeARequestJudgesWhatTheNameResolvesToNow() {
        AlarmWebhookTargetPolicy open = policy("");
        assertThat(open.problemWith(URI.create("https://hooks.example.com/a"))).isNull();
        assertThat(open.problemWith(URI.create("https://rebind.example.com/a"))).isEqualTo(FORBIDDEN);
    }
}
