package com.example.globalnewsenginev1.statistics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

@RestController
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsReport> statistics(@RequestParam Map<String, String> parameters) {
        if (!Set.of("from", "to", "hours").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Only from, to and hours parameters are supported");
        }
        if (parameters.containsKey("hours")) {
            if (parameters.size() != 1) throw new IllegalArgumentException("hours cannot be combined with from or to");
            int hours;
            try { hours = Integer.parseInt(parameters.get("hours")); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("hours must be an integer between 1 and 744"); }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recent(hours));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.report(parameters.get("from"), parameters.get("to")));
    }
}
