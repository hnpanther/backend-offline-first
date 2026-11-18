package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.DeviceRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
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

    static final String PERMISSION_WIPE = "POST:/devices/{id}/wipe";
    static final String PERMISSION_WIPE_CANCEL = "POST:/devices/{id}/wipe/cancel";

    private final DeviceEnrollmentService deviceEnrollmentService;
    private final AppSettingsService appSettingsService;
    private final UserRepository userRepository;
    private final ApiSessionRepository apiSessionRepository;

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
        model.addAttribute("backlogs", backlogs(devices.getContent()));
        model.addAttribute("now", now);
        WebListSupport.addPagination(model, devices, null, page, pageSize);
        return "devices";
    }

    /**
     * The confirmation page for revoking a device — and, on a device already revoked, for ordering
     * or withdrawing a wipe (V28).
     *
     * <p>A page rather than a {@code confirm()} box because the decision needs facts a one-line
     * dialog cannot carry: how many rounds the tablet last said it had not delivered, when it said
     * so, and that a wipe destroys exactly those. Guarded by the revoke authority itself — showing
     * the facts is part of that action, not a permission of its own.
     */
    @GetMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/revoke')")
    public String revokeConfirm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Device device = deviceEnrollmentService.find(id).orElse(null);
        if (device == null) {
            ra.addFlashAttribute("errorMessage", "دستگاه پیدا نشد.");
            return "redirect:/devices";
        }
        model.addAttribute("activePage", "devices");
        model.addAttribute("device", device);
        model.addAttribute("backlog", backlogs(List.of(device)).get(device.getId()));
        model.addAttribute("userNames", userNames(Stream.of(device)));
        model.addAttribute("enrollmentEnabled", appSettingsService.isDeviceEnrollmentEnabled());
        model.addAttribute("canWipe", SecurityUtils.hasPermission(PERMISSION_WIPE));
        model.addAttribute("canCancelWipe", SecurityUtils.hasPermission(PERMISSION_WIPE_CANCEL));
        model.addAttribute("now", System.currentTimeMillis());
        return "device-revoke";
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

    /**
     * Revokes a device, or turns down a request.
     *
     * <p>{@code wipe=true} also orders the tablet to delete what the app holds (V28). That needs
     * the wipe authority as well, checked here because one handler serves both: a caller without
     * it is refused <b>entirely</b> — revoking without the wipe that was asked for would leave an
     * administrator believing a lost tablet will be emptied when it never will.
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/revoke')")
    public String revoke(@PathVariable Long id,
                         @RequestParam(defaultValue = "false") boolean wipe,
                         RedirectAttributes ra) {
        if (wipe && !SecurityUtils.hasPermission(PERMISSION_WIPE)) {
            ra.addFlashAttribute("errorMessage", "شما مجوز دستور پاک‌سازی دستگاه را ندارید؛ دستگاه لغو نشد.");
            return "redirect:/devices/" + id + "/revoke";
        }
        try {
            DeviceEnrollmentService.RevokeResult result = deviceEnrollmentService.revoke(
                    id, SecurityUtils.currentUserId(), System.currentTimeMillis(), wipe);
            boolean enrollmentEnabled = appSettingsService.isDeviceEnrollmentEnabled();
            ra.addFlashAttribute("successMessage", result.reason() == DeviceRevokeReason.REJECTED
                    ? FaMessages.deviceRequestRejected()
                    : result.wipeOrdered()
                    ? FaMessages.deviceRevokedWithWipe(result.sessionsRevoked(), enrollmentEnabled)
                    : FaMessages.deviceRevoked(result.sessionsRevoked(), enrollmentEnabled));
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/devices";
    }

    /** Orders an already-revoked tablet to delete what the app holds (V28). */
    @PostMapping("/{id}/wipe")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/wipe')")
    public String orderWipe(@PathVariable Long id, RedirectAttributes ra) {
        try {
            deviceEnrollmentService.orderWipe(id, SecurityUtils.currentUserId(), System.currentTimeMillis());
            ra.addFlashAttribute("successMessage", FaMessages.deviceWipeOrdered());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/devices?filter=revoked";
    }

    /** Withdraws a wipe order the tablet has not been handed yet (V28). */
    @PostMapping("/{id}/wipe/cancel")
    @PreAuthorize("hasAuthority('POST:/devices/{id}/wipe/cancel')")
    public String cancelWipe(@PathVariable Long id, RedirectAttributes ra) {
        try {
            deviceEnrollmentService.cancelWipe(id, SecurityUtils.currentUserId(), System.currentTimeMillis());
            ra.addFlashAttribute("successMessage", FaMessages.deviceWipeCancelled());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/devices?filter=revoked";
    }

    /**
     * Turns down every request still waiting — one click against a flood holding the pending
     * ceiling full (issue B-12). A literal path, so it is matched before {@code /{id}/…}.
     */
    @PostMapping("/pending/reject")
    @PreAuthorize("hasAuthority('POST:/devices/pending/reject')")
    public String rejectAllPending(RedirectAttributes ra) {
        int rejected = deviceEnrollmentService.rejectAllPending(
                SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", FaMessages.devicePendingRequestsRejected(rejected));
        return "redirect:/devices";
    }

    /**
     * Display names for everyone who issued, approved or revoked something on this page.
     *
     * <p>A {@link HashMap}, and that is not incidental: the template reads it with
     * {@code .get(id)}, and the id is null for every device nobody has acted on yet. A
     * {@code Map.of} or {@code Map.copyOf} throws on {@code get(null)}; a HashMap answers null.
     */
    /**
     * The latest backlog each device's tablet reported, keyed by device id. A {@link HashMap} for
     * the same reason as {@link #userNames}: the template asks {@code .get(id)} for every row,
     * and most devices have never reported.
     */
    private Map<Long, ApiSessionRepository.DeviceBacklogRow> backlogs(List<Device> devices) {
        List<Long> ids = devices.stream().map(Device::getId).filter(Objects::nonNull).toList();
        Map<Long, ApiSessionRepository.DeviceBacklogRow> byDevice = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ApiSessionRepository.DeviceBacklogRow row : apiSessionRepository.latestBacklogForDevices(ids)) {
                byDevice.put(row.getDeviceId(), row);
            }
        }
        return byDevice;
    }

    private Map<Long, String> userNames(Stream<Device> devices) {
        Set<Long> ids = new HashSet<>();
        devices.forEach(d -> Stream.of(d.getCodeIssuedBy(), d.getApprovedBy(), d.getRevokedBy(),
                        d.getWipeRequestedBy())
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
