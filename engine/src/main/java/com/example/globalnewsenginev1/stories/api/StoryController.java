package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.StoryDetail;
import com.example.globalnewsenginev1.stories.query.StoryCountry;
import com.example.globalnewsenginev1.stories.query.StoryPage;
import com.example.globalnewsenginev1.stories.query.StoryMapPosition;
import com.example.globalnewsenginev1.stories.query.StoryMapMarker;
import com.example.globalnewsenginev1.stories.query.StoryQueryService;
import com.example.globalnewsenginev1.stories.query.StorySearchCriteria;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

@RestController
@RequestMapping("/stories")
public class StoryController {

    private static final Set<String> LIST_PARAMETERS = Set.of(
            "offset", "limit", "sort", "direction", "from", "to", "q", "domain", "country", "region",
            "bounds");
    private static final Set<String> MAP_PARAMETERS = Set.of("from", "to", "q", "domain", "country", "region");

    private final StoryQueryService queryService;

    public StoryController(StoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/countries")
    public List<StoryCountry> countries() {
        return queryService.countries();
    }

    @GetMapping("/map-positions")
    public List<StoryMapPosition> mapPositions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String region,
            @RequestParam Map<String, String> parameters
    ) {
        validateParameters(parameters, MAP_PARAMETERS);
        return queryService.mapPositions(new StorySearchCriteria(parseInstant("from", from), parseInstant("to", to),
                query, domain, parseLocationCode("country", country), parseLocationCode("region", region), null));
    }

    @GetMapping("/map-markers")
    public List<StoryMapMarker> mapMarkers(
            @RequestParam(required = false) String ids,
            @RequestParam Map<String, String> parameters
    ) {
        validateParameters(parameters, Set.of("ids"));
        if (ids == null || ids.isBlank()) throw new IllegalArgumentException("ids must contain UUIDs");
        String[] values = ids.split(",", -1);
        if (values.length > StoryQueryService.MAX_MAP_MARKERS) {
            throw new IllegalArgumentException("ids must not exceed " + StoryQueryService.MAX_MAP_MARKERS + " UUIDs");
        }
        List<UUID> parsed = Arrays.stream(values).map(value -> {
            try {
                UUID id = UUID.fromString(value);
                if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
                return id;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("ids must contain comma-separated UUIDs");
            }
        }).toList();
        return queryService.mapMarkers(parsed);
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
        validateParameters(parameters, LIST_PARAMETERS);
        return queryService.stories(offset, limit, sort, direction,
                new StorySearchCriteria(parseInstant("from", from), parseInstant("to", to), query, domain,
                        parseLocationCode("country", country), parseLocationCode("region", region),
                        parseBounds(bounds)));
    }

    private void validateParameters(Map<String, String> parameters, Set<String> allowed) {
        parameters.keySet().stream()
                .filter(parameter -> !allowed.contains(parameter))
                .findFirst()
                .ifPresent(parameter -> {
                    throw new IllegalArgumentException("unknown parameter: " + parameter);
                });
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
