package com.hnp.backendofflinefirst.service.alarm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where an alarm webhook may point (V21) — the guard that replaces «the URL is configuration».
 *
 * <h2>The threat</h2>
 *
 * <p>An outbound address an administrator can type is a way to make this server send requests
 * somewhere its network reaches and the administrator's does not (server-side request forgery):
 * the database on the loopback interface, a cloud metadata service on a link-local address, an
 * internal admin console. Three things here, and one in the dispatcher, keep that bounded:
 *
 * <ol>
 *   <li><b>Addresses that are never a receiver are refused</b> — loopback ({@code 127.0.0.0/8},
 *       {@code ::1}), link-local ({@code 169.254.0.0/16}, where {@code 169.254.169.254} serves
 *       cloud metadata, and {@code fe80::/10}), unspecified ({@code 0.0.0.0}, {@code ::}) and
 *       multicast. Private ranges ({@code 10/8}, {@code 192.168/16}, …) are <em>allowed</em>:
 *       a plant's SMS gateway or CMMS is normally exactly there.</li>
 *   <li><b>{@code app.alarm.webhook.allowed-hosts}</b>, when set, is the complete list of hosts an
 *       endpoint may name — {@code hooks.example.com}, {@code *.plant.local} or an address. The
 *       production setting: it moves «where may alarms go» back under the control of whoever
 *       runs the server, while leaving the day-to-day choice in the panel. A host listed
 *       <em>exactly</em> (not by wildcard) skips the address check, so a receiver on the same
 *       machine can be allowed deliberately; nothing else can reach loopback.</li>
 *   <li><b>The check is made at save and again before every request</b>, on the addresses the
 *       name resolves to <em>then</em>, so a name re-pointed at an internal address after it was
 *       saved (DNS rebinding) is refused when it matters. The HTTP client resolves once more
 *       when it connects; that window is milliseconds, and the allow-list closes it.</li>
 * </ol>
 *
 * <p>The dispatcher adds the fourth: it never follows a redirect and never records what a receiver
 * answered — only the status code — so the delivery log cannot be used to read another service.
 */
@Component
public class AlarmWebhookTargetPolicy {

    /** How a host name is resolved; replaceable in tests so they need no DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final Resolver resolver;

    @Autowired
    public AlarmWebhookTargetPolicy(@Value("${app.alarm.webhook.allowed-hosts:}") String allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    AlarmWebhookTargetPolicy(String allowedHosts, Resolver resolver) {
        this.allowedHosts = allowedHosts == null ? List.of()
                : Arrays.stream(allowedHosts.split(","))
                    .map(h -> h.trim().toLowerCase(Locale.ROOT))
                    .filter(h -> !h.isEmpty())
                    .toList();
        this.resolver = resolver;
    }

    /** Whether the deployment has bounded the hosts at all — the panel says so when it has not. */
    public boolean hasAllowList() {
        return !allowedHosts.isEmpty();
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Parses and checks an address typed into the panel.
     *
     * @return the normalised URI
     * @throws IllegalArgumentException with a message ErrorTranslator renders in Persian
     */
    public URI validate(String raw) {
        URI uri = parse(raw);
        String problem = problemWith(uri);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return uri;
    }

    /**
     * The check made before every request.
     *
     * @return null when the request may be made, otherwise why not — written to the delivery's
     *         {@code last_error}, so it names the rule and never an address the name resolved to
     */
    public String problemWith(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean listedExactly = allowedHosts.contains(host);
        if (hasAllowList() && !listedExactly && allowedHosts.stream().noneMatch(entry -> wildcardMatches(entry, host))) {
            return "Webhook host is not in app.alarm.webhook.allowed-hosts.";
        }
        if (listedExactly) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(stripBrackets(host));
        } catch (UnknownHostException e) {
            return "Webhook host cannot be resolved.";
        }
        for (InetAddress address : addresses) {
            if (isNeverAReceiver(address)) {
                return "Webhook host resolves to a loopback, link-local or unspecified address.";
            }
        }
        return null;
    }

    /** Absolute http(s), a host, and no credentials in the address itself. */
    public static URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required.");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook URL must be an absolute http(s) URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Webhook URL must be an absolute http(s) URL.");
        }
        if (uri.getRawUserInfo() != null) {
            // A password in the address would be shown on the page and written to every log line
            // that names the target; the API key field exists for exactly this.
            throw new IllegalArgumentException("Webhook URL must not contain a user name or password.");
        }
        if (uri.toString().length() > 2000) {
            throw new IllegalArgumentException("Webhook URL is too long.");
        }
        return uri;
    }

    static boolean isNeverAReceiver(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }

    private static boolean wildcardMatches(String entry, String host) {
        if (!entry.startsWith("*.")) {
            return false;
        }
        String suffix = entry.substring(1); // ".plant.local"
        return host.endsWith(suffix) && host.length() > suffix.length();
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
