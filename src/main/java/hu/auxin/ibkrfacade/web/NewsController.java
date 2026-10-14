package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.news.NewsArticle;
import hu.auxin.ibkrfacade.news.NewsHeadline;
import hu.auxin.ibkrfacade.news.NewsProviderDto;
import hu.auxin.ibkrfacade.news.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "News", description = "Requires news subscriptions on the IBKR account. Missing permissions are "
        + "reported as 502 TWS_ERROR with the tws_error_code.")
@RestController
public class NewsController {

    private final NewsService newsService;

    NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @Operation(summary = "News providers available for the account (reqNewsProviders)")
    @GetMapping("/news/providers")
    List<NewsProviderDto> getProviders() {
        return newsService.getProviders();
    }

    @Operation(summary = "Historical news headlines of an instrument (reqHistoricalNews)")
    @GetMapping("/news/{conid}")
    List<NewsHeadline> getHeadlines(
            @PathVariable int conid,
            @Parameter(description = "Provider codes separated by '+', defaults to every available provider", example = "BRFG+DJNL") @RequestParam(required = false) String providers,
            @Parameter(description = "Number of headlines (1-300)") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Start time (yyyy-MM-dd HH:mm:ss.0, UTC)") @RequestParam(required = false) String start,
            @Parameter(description = "End time (yyyy-MM-dd HH:mm:ss.0, UTC)") @RequestParam(required = false) String end) {
        return newsService.getHeadlines(conid, providers, limit, start, end);
    }

    @Operation(summary = "News article body (reqNewsArticle)")
    @GetMapping("/news/article/{providerCode}/{articleId}")
    NewsArticle getArticle(@PathVariable String providerCode, @PathVariable String articleId) {
        return newsService.getArticle(providerCode, articleId);
    }
}
