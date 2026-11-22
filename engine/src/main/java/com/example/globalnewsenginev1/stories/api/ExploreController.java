package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.ExploreQueryService;
import com.example.globalnewsenginev1.stories.query.ExploreQueryService.ArticleResult;
import com.example.globalnewsenginev1.stories.query.ExploreQueryService.Page;
import com.example.globalnewsenginev1.stories.query.ExploreQueryService.StoryResult;
import com.example.globalnewsenginev1.stories.query.StoryCountry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/explore")
public class ExploreController {

    private final ExploreQueryService query;

    public ExploreController(ExploreQueryService query) {
        this.query = query;
    }

    @GetMapping("/stories")
    public Page<StoryResult> stories(
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(name = "q", required = false) String text,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "1") String page) {
        return query.stories(period, text, country, domain, pageNumber(page));
    }

    @GetMapping("/articles")
    public Page<ArticleResult> articles(
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(name = "q", required = false) String text,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "1") String page) {
        return query.articles(period, text, country, domain, pageNumber(page));
    }

    @GetMapping("/countries")
    public List<StoryCountry> countries(@RequestParam(defaultValue = "stories") String view) {
        return query.countries(view);
    }

    private static int pageNumber(String page) {
        try {
            if (page.chars().allMatch(character -> character >= '0' && character <= '9')) {
                int number = Integer.parseInt(page);
                if (number > 0) return number;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("page must be a positive integer", exception);
        }
        throw new IllegalArgumentException("page must be a positive integer");
    }
}
