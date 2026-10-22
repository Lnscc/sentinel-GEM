package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.StoryArticle;
import com.example.globalnewsenginev1.stories.query.StoryDetail;
import com.example.globalnewsenginev1.stories.query.StoryLocation;
import com.example.globalnewsenginev1.stories.query.StoryMember;
import com.example.globalnewsenginev1.stories.query.StoryPage;
import com.example.globalnewsenginev1.stories.query.StoryQueryService;
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
                "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "GDELT_EVENT_ACTION");
        queryService.page = new StoryPage(
                List.of(new StorySummary(id, "ACTIVE", from, to, location, article, 1)), 0, 1, 1);
        queryService.details.put(id, new StoryDetail(
                id, "ACTIVE", from, to, location, article,
                List.of(new StoryMember(article, "NEW_COMPONENT"))));

        mockMvc.perform(get("/stories")
                        .param("limit", "1")
                        .param("sort", "articleCount")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].id").value(id.toString()))
                .andExpect(jsonPath("$.stories[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.stories[0].location.name").value("Berlin, Germany"))
                .andExpect(jsonPath("$.stories[0].location.source").value("GDELT_EVENT_ACTION"))
                .andExpect(jsonPath("$.stories[0].representativeArticle.title").value("Headline"))
                .andExpect(jsonPath("$.stories[0].memberCount").value(1));
        mockMvc.perform(get("/stories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.location.latitude").value(52.52))
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
    }

    private static class StubStoryQueryService extends StoryQueryService {
        private StoryPage page;
        private final Map<UUID, StoryDetail> details = new HashMap<>();

        StubStoryQueryService() {
            super(new JdbcTemplate(), "unused");
        }

        @Override
        public StoryPage stories(int offset, int limit, String sort, String direction) {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (!List.of("time", "articleCount").contains(sort)) {
                throw new IllegalArgumentException("invalid sort");
            }
            if (!List.of("asc", "desc").contains(direction)) {
                throw new IllegalArgumentException("invalid direction");
            }
            return page;
        }

        @Override
        public Optional<StoryDetail> story(UUID id) {
            return Optional.ofNullable(details.get(id));
        }
    }
}
