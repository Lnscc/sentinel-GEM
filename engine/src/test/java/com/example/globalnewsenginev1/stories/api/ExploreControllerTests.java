package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.ExploreQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ExploreControllerTests {

    @Test
    void defaultsToTheLast24HoursAndReturnsExplicitEmptyCorpusBounds() throws Exception {
        ExploreQueryService query = new ExploreQueryService(new JdbcTemplate(), "version") {
            @Override
            public Page<StoryResult> stories(String period, String text, String country, String domain, int page) {
                assertThat(period).isEqualTo("24h");
                assertThat(page).isEqualTo(1);
                return new Page<>(List.of(), 0, 1, 50, null, null);
            }
        };

        standaloneSetup(new ExploreController(query)).build().perform(get("/explore/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.availableFrom").hasJsonPath())
                .andExpect(jsonPath("$.availableFrom").doesNotExist())
                .andExpect(jsonPath("$.availableTo").hasJsonPath())
                .andExpect(jsonPath("$.availableTo").doesNotExist());
    }

    @Test
    void rejectsInvalidRequestOptionsBeforeQueryingTheDatabase() throws Exception {
        var mvc = standaloneSetup(new ExploreController(new ExploreQueryService(new JdbcTemplate(), "version")))
                .setControllerAdvice(new StoryApiExceptionHandler()).build();

        for (String page : List.of("-1", "0", "1.1", "2147483648")) {
            mvc.perform(get("/explore/articles").param("page", page))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        }
        for (String parameter : List.of("q", "country", "domain")) {
            mvc.perform(get("/explore/articles").param(parameter, "abc" + (char) 0))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        }
        mvc.perform(get("/explore/stories").param("period", "14d"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get("/explore/countries").param("view", "map"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }
}
