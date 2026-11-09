package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.DeviceRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.DeviceEnrollmentService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The approver's page: pending enrolment requests to issue a code for, and the devices already
 * enrolled, to revoke.
 *
 * <p>Usable whether or not enrolment is switched on, and that is deliberate — revoking a device
 * must always be possible. The page says plainly when the switch is off, because then nothing
 * here affects a login.
 */
@Controller
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceWebController {

    private static final String FILTER_APPROVED = "approved";
    private static final String FILTER_REVOKED = "revoked";
    private static final String FILTER_ALL = "all";

    private final DeviceEnrollmentService deviceEnrollmentService;
    private final AppSettingsService appSettingsService;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/devices')")
    public String list(@RequestParam(defaultValue = FILTER_APPROVED) String filter,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        long now = System.currentTimeMillis();
        String activeFilter = switch (filter) {
            case FILTER_REVOKED, FILTER_ALL -> filter;
            default -> FILTER_APPROVED;
        };
        Collection<DeviceStatus> statuses = switch (activeFilter) {
            case FILTER_REVOKED -> List.of(DeviceStatus.REVOKED);
            case FILTER_ALL -> List.of(DeviceStatus.values());
            default -> List.of(DeviceStatus.APPROVED);
        };
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Page<Device> devices = deviceEnrollmentService.list(statuses, WebListSupport.pageable(page, pageSize));
        List<Device> pending = deviceEnrollmentService.livePending(now);

        model.addAttribute("activePage", "devices");
        model.addAttribute("enrollmentEnabled", appSettingsService.isDeviceEnrollmentEnabled());
        model.addAttribute("pending", pending);
        model.addAttribute("devices", devices.getContent());
        model.addAttribute("filter", activeFilter);
        model.addAttribute("filterQuery", "&filter=" + activeFilter);
        model.addAttribute("approvedCount", deviceEnrollmentService.countApproved());
        model.addAttribute("userNames", userNames(Stream.concat(pending.stream(), devices.getContent().stream())));
        model.addAttribute("now", now);
        WebListSupport.addPagination(model, devices, null, page, pageSize);
        return "devices";
    }

    @PostMapping("/{id}/code")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/code')")
    public String issueCode(@PathVariable Long id, RedirectAttributes ra) {
        try {
            DeviceDtos.IssuedCode issued = deviceEnrollmentService.issueCode(
                    id, SecurityUtils.currentUserId(), System.currentTimeMillis());
            // Flash, not a query parameter: the code must not land in a URL, a browser history
            // or an access log. The page shows it once and it is gone on the next request.
            ra.addFlashAttribute("issuedCode", issued);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/devices";
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/revoke')")
    public String revoke(@PathVariable Long id, RedirectAttributes ra) {
        try {
            DeviceEnrollmentService.RevokeResult result = deviceEnrollmentService.revoke(
                    id, SecurityUtils.currentUserId(), System.currentTimeMillis());
            ra.addFlashAttribute("successMessage", result.reason() == DeviceRevokeReason.REJECTED
                    ? FaMessages.deviceRequestRejected()
                    : FaMessages.deviceRevoked(result.sessionsRevoked()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/devices";
    }

    /**
     * Display names for everyone who issued, approved or revoked something on this page.
     *
     * <p>A {@link HashMap}, and that is not incidental: the template reads it with
     * {@code .get(id)}, and the id is null for every device nobody has acted on yet. A
     * {@code Map.of} or {@code Map.copyOf} throws on {@code get(null)}; a HashMap answers null.
     */
    private Map<Long, String> userNames(Stream<Device> devices) {
        Set<Long> ids = new HashSet<>();
        devices.forEach(d -> Stream.of(d.getCodeIssuedBy(), d.getApprovedBy(), d.getRevokedBy())
                .filter(Objects::nonNull)
                .forEach(ids::add));
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User user : userRepository.findAllById(ids)) {
                String full = user.getFullName();
                names.put(user.getId(), full != null && !full.isBlank() ? full : user.getUsername());
            }
        }
        return names;
    }
}
