package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.service.DemoDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/admin/demo"})
@PreAuthorize(value="hasRole('ADMIN')")
public class DemoDataController {
    private final DemoDataService demoDataService;

    public DemoDataController(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @PostMapping(value={"/load"})
    public ResponseEntity<DemoDataService.DemoLoadResult> loadDemo(@RequestBody(required=false) DemoLoadRequest request) {
        String scenario = request != null && request.scenario() != null ? request.scenario() : "default";
        DemoDataService.DemoLoadResult result = demoDataService.loadDemoData(scenario);
        if (!result.success()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        return ResponseEntity.ok(result);
    }

    public record DemoLoadRequest(String scenario) {
    }
}
