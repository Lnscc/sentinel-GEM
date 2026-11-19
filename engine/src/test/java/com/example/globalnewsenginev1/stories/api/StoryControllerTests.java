package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.StoryArticle;
import com.example.globalnewsenginev1.stories.query.StoryCountry;
import com.example.globalnewsenginev1.stories.query.StoryDetail;
import com.example.globalnewsenginev1.stories.query.StoryLocation;
import com.example.globalnewsenginev1.stories.query.StoryMapPosition;
import com.example.globalnewsenginev1.stories.query.StoryMapMarker;
import com.example.globalnewsenginev1.stories.query.StoryMember;
import com.example.globalnewsenginev1.stories.query.StoryPage;
import com.example.globalnewsenginev1.stories.query.StoryQueryService;
import com.example.globalnewsenginev1.stories.query.StorySearchCriteria;
import com.example.globalnewsenginev1.stories.query.StorySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StoryControllerTests {

    private StubStoryQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = new StubStoryQueryService();
        mockMvc = standaloneSetup(new StoryController(queryService))
                .setControllerAdvice(new StoryApiExceptionHandler())
                .build();
    }

    @Test
    void returnsCompactMapPositionsWithExplicitFiltersAndNoViewportOrPagination() throws Exception {
        UUID id = new UUID(1, 1);
        queryService.positions = List.of(new StoryMapPosition(id, 52.52, 13.405));
        mockMvc.perform(get("/stories/map-positions").param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z").param("q", "Berlin")
                        .param("domain", "example.org").param("country", "GM").param("region", "GM16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].latitude").value(52.52))
                .andExpect(jsonPath("$[0].longitude").value(13.405))
                .andExpect(jsonPath("$[0].length()").value(3));
        assertThat(queryService.criteria).isEqualTo(new StorySearchCriteria(
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                "Berlin", "example.org", "GM", "GM16", null));
        queryService.positions = List.of();
        mockMvc.perform(get("/stories/map-positions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        for (String parameter : List.of("bounds", "limit", "offset", "sort", "unexpected")) {
            mockMvc.perform(get("/stories/map-positions").param(parameter, "1"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        }
        mockMvc.perform(get("/stories/map-positions").param("from", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories/map-positions").param("country", "GM!"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void returnsCompactMapMetadataOnlyForAValidBoundedIdBatch() throws Exception {
        UUID id = new UUID(1, 1);
        queryService.markers = List.of(new StoryMapMarker(id, "Headline", null));
        mockMvc.perform(get("/stories/map-markers").param("ids", id + "," + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].title").value("Headline"))
                .andExpect(jsonPath("$[0].imageUrl").isEmpty())
                .andExpect(jsonPath("$[0].length()").value(3));
        assertThat(queryService.markerIds).containsExactly(id, id);
        String hundredIds = String.join(",", java.util.Collections.nCopies(100, id.toString()));
        mockMvc.perform(get("/stories/map-markers").param("ids", hundredIds)).andExpect(status().isOk());
        assertThat(queryService.markerIds).hasSize(100);
        queryService.markers = List.of();
        mockMvc.perform(get("/stories/map-markers").param("ids", new UUID(9, 9).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/stories/map-markers"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        for (String invalid : List.of("", "not-a-uuid", "1-1-1-1-1", id + ",", hundredIds + "," + id)) {
            mockMvc.perform(get("/stories/map-markers").param("ids", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_request"))
                    .andExpect(jsonPath("$.message").isString());
        }
        mockMvc.perform(get("/stories/map-markers").param("ids", id.toString()).param("bounds", "0,0,1,1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void returnsAvailableCountriesWithoutPagination() throws Exception {
        mockMvc.perform(get("/stories/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("GM"))
                .andExpect(jsonPath("$[0].name").value("Deutschland"));
    }

    @Test
    void returnsEmptyPageWithDefaults() throws Exception {
        queryService.page = new StoryPage(List.of(), 0, 20, 0);

        mockMvc.perform(get("/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories").isEmpty())
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void returnsStableListAndDetailContracts() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Instant from = Instant.parse("2026-09-01T10:00:00Z");
        Instant to = Instant.parse("2026-09-01T11:00:00Z");
        StoryArticle article = new StoryArticle(7, "https://example.org/story", "example.org", from,
                "Headline", from, "https://example.org/image.jpg");
        StoryLocation location = new StoryLocation(
                "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "GDELT_EVENT_ACTION",
                100, 2, 2);
        queryService.page = new StoryPage(
                List.of(new StorySummary(id, "ACTIVE", from, to, location, List.of(location), article, 1, 1)),
                0, 1, 1);
        queryService.details.put(id, new StoryDetail(
                id, "ACTIVE", from, to, location, List.of(location), article,
                List.of(new StoryMember(article, "NEW_COMPONENT"))));

        mockMvc.perform(get("/stories")
                        .param("limit", "1")
                        .param("sort", "priority")
                        .param("direction", "asc")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z")
                        .param("q", "Berlin")
                        .param("domain", "example.org")
                        .param("country", "GM")
                        .param("region", "GM16")
                        .param("bounds", "5,47,16,55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].id").value(id.toString()))
                .andExpect(jsonPath("$.stories[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.stories[0].location.name").value("Berlin, Germany"))
                .andExpect(jsonPath("$.stories[0].location.source").value("GDELT_EVENT_ACTION"))
                .andExpect(jsonPath("$.stories[0].location.confidence").value(100))
                .andExpect(jsonPath("$.stories[0].location.supportingArticles").value(2))
                .andExpect(jsonPath("$.stories[0].location.supportingDomains").value(2))
                .andExpect(jsonPath("$.stories[0].locations[0].name").value("Berlin, Germany"))
                .andExpect(jsonPath("$.stories[0].representativeArticle.title").value("Headline"))
                .andExpect(jsonPath("$.stories[0].memberCount").value(1))
                .andExpect(jsonPath("$.stories[0].sourceCount").value(1));
        assertThat(queryService.criteria).isEqualTo(new StorySearchCriteria(
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"), "Berlin", "example.org", "GM", "GM16",
                new StorySearchCriteria.Bounds(5, 47, 16, 55)));
        mockMvc.perform(get("/stories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.location.latitude").value(52.52))
                .andExpect(jsonPath("$.locations[0].confidence").value(100))
                .andExpect(jsonPath("$.members[0].article.id").value(7))
                .andExpect(jsonPath("$.members[0].assignmentReason").value("NEW_COMPONENT"));
    }

    @Test
    void returnsNotFoundAndRejectsInvalidRequests() throws Exception {
        UUID missing = UUID.fromString("10000000-0000-0000-0000-000000000099");
        mockMvc.perform(get("/stories/{id}", missing)).andExpect(status().isNotFound());
        mockMvc.perform(get("/stories/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("offset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("unexpected", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("sort", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("direction", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("from", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("region", "GM-16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("bounds", "5,55,16,47"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories").param("bounds", "not,a,map,area"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mockMvc.perform(get("/stories")
                        .param("from", "2026-09-02T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    private static class StubStoryQueryService extends StoryQueryService {
        private StoryPage page;
        private StorySearchCriteria criteria;
        private List<StoryMapPosition> positions = List.of();
        private List<StoryMapMarker> markers = List.of();
        private List<UUID> markerIds;
        private final Map<UUID, StoryDetail> details = new HashMap<>();

        StubStoryQueryService() {
            super(new JdbcTemplate(), "unused");
        }

        @Override
        public List<StoryMapPosition> mapPositions(StorySearchCriteria criteria) {
            this.criteria = criteria;
            return positions;
        }

        @Override
        public List<StoryMapMarker> mapMarkers(List<UUID> ids) {
            markerIds = ids;
            return markers;
        }

        @Override
        public List<StoryCountry> countries() {
            return List.of(new StoryCountry("GM", "Deutschland"));
        }

        @Override
        public StoryPage stories(int offset, int limit, String sort, String direction,
                                 StorySearchCriteria criteria) {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (!List.of("time", "articleCount", "priority").contains(sort)) {
                throw new IllegalArgumentException("invalid sort");
            }
            if (!List.of("asc", "desc").contains(direction)) {
                throw new IllegalArgumentException("invalid direction");
            }
            if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
                throw new IllegalArgumentException("invalid range");
            }
            if (criteria.query() != null && criteria.query().length() > 200) {
                throw new IllegalArgumentException("invalid query");
            }
            if (criteria.bounds() != null && criteria.bounds().south() >= criteria.bounds().north()) {
                throw new IllegalArgumentException("invalid bounds");
            }
            this.criteria = criteria;
            return page;
        }

        @Override
        public Optional<StoryDetail> story(UUID id) {
            return Optional.ofNullable(details.get(id));
        }
    }
}
