package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.StoryDetail;
import com.example.globalnewsenginev1.stories.query.StoryPage;
import com.example.globalnewsenginev1.stories.query.StoryQueryService;
import com.example.globalnewsenginev1.stories.query.StorySearchCriteria;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/stories")
public class StoryController {

    private static final Set<String> LIST_PARAMETERS = Set.of(
            "offset", "limit", "sort", "direction", "from", "to", "q", "domain", "country", "region",
            "bounds");

    private final StoryQueryService queryService;

    public StoryController(StoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public StoryPage stories(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "time") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String bounds,
            @RequestParam Map<String, String> parameters
    ) {
        parameters.keySet().stream()
                .filter(parameter -> !LIST_PARAMETERS.contains(parameter))
                .findFirst()
                .ifPresent(parameter -> {
                    throw new IllegalArgumentException("unknown parameter: " + parameter);
                });
        return queryService.stories(offset, limit, sort, direction,
                new StorySearchCriteria(parseInstant("from", from), parseInstant("to", to), query, domain,
                        parseLocationCode("country", country), parseLocationCode("region", region),
                        parseBounds(bounds)));
    }

    private StorySearchCriteria.Bounds parseBounds(String value) {
        if (value == null) return null;
        String[] coordinates = value.split(",", -1);
        if (coordinates.length != 4) {
            throw new IllegalArgumentException("bounds must contain west,south,east,north");
        }
        try {
            return new StorySearchCriteria.Bounds(
                    Double.parseDouble(coordinates[0]), Double.parseDouble(coordinates[1]),
                    Double.parseDouble(coordinates[2]), Double.parseDouble(coordinates[3]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("bounds must contain four numbers");
        }
    }

    private String parseLocationCode(String parameter, String value) {
        if (value == null) return null;
        String code = value.trim();
        if (!code.matches("[A-Za-z0-9]{1,32}")) {
            throw new IllegalArgumentException(parameter + " must contain 1 to 32 letters or digits");
        }
        return code;
    }

    private Instant parseInstant(String parameter, String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(parameter + " must be an ISO-8601 instant");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoryDetail> story(@PathVariable UUID id) {
        return queryService.story(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
