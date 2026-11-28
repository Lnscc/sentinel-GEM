package com.example.globalnewsenginev1.statistics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.Map;
import java.util.Set;

@RestController
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/statistics")
    public WebAsyncTask<ResponseEntity<StatisticsReport>> statistics(@RequestParam Map<String, String> parameters) {
        if (!Set.of("from", "to", "hours").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Only from, to and hours parameters are supported");
        }
        if (parameters.containsKey("hours")) {
            if (parameters.size() != 1) throw new IllegalArgumentException("hours cannot be combined with from or to");
            int hours;
            try { hours = Integer.parseInt(parameters.get("hours")); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("hours must be an integer between 1 and 744"); }
            return execute(() -> service.recent(hours));
        }
        return execute(() -> service.report(parameters.get("from"), parameters.get("to")));
    }

    private WebAsyncTask<ResponseEntity<StatisticsReport>> execute(java.util.concurrent.Callable<StatisticsReport> report) {
        StatisticsQueryBudget budget = new StatisticsQueryBudget();
        WebAsyncTask<ResponseEntity<StatisticsReport>> task = new WebAsyncTask<>(46_000L, () -> {
            try (budget) {
                budget.bind();
                StatisticsReport result = report.call();
                StatisticsQueryBudget.checkCurrent();
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
            }
        });
        task.onTimeout(() -> {
            budget.cancel();
            throw new org.springframework.dao.QueryTimeoutException("Statistics request budget exhausted");
        });
        task.onError(() -> {
            budget.cancel();
            throw new org.springframework.dao.QueryTimeoutException("Statistics request aborted");
        });
        task.onCompletion(budget::cancel);
        return task;
    }
}
