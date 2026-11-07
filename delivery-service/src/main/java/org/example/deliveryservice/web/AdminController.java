package org.example.deliveryservice.web;

import org.example.deliveryservice.kafka.DlqReplayService;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Operator endpoints. Not routed through the gateway; keep internal. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DlqReplayService replayService;
    private final DeliveryStateRepository state;

    @PostMapping("/dlq/replay")
    public DlqReplayService.Result replay(@RequestParam(defaultValue = "100") int limit) {
        return replayService.replay(limit);
    }

    @PutMapping("/opt-out")
    public ResponseEntity<Void> optOut(
            @RequestParam String tenantId, @RequestParam String recipient) {
        state.addOptOut(tenantId, recipient);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/opt-out")
    public ResponseEntity<Void> optIn(
            @RequestParam String tenantId, @RequestParam String recipient) {
        state.removeOptOut(tenantId, recipient);
        return ResponseEntity.noContent().build();
    }
}
