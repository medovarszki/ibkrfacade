package hu.auxin.ibkrfacade.news;

import com.ib.client.NewsProvider;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * News through the TWS API. Requires news subscriptions on the IBKR account (some providers are free, eg. BRFG,
 * BRFUPDN, DJNL), otherwise TWS answers with an error.
 */
@Service
public class NewsService {

    private static final Pattern PROVIDER_CODES = Pattern.compile("[A-Za-z0-9_]+(\\+[A-Za-z0-9_]+)*");

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;

    /**
     * reqNewsProviders has no request id, the pending request is identified by this
     */
    private volatile int providersRequestId = -1;

    public NewsService(TwsConnection connection, TwsRequestRegistry requestRegistry) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
    }

    public synchronized List<NewsProviderDto> getProviders() {
        return requestRegistry.call(id -> {
            providersRequestId = id;
            connection.client().reqNewsProviders();
        });
    }

    /**
     * @param providerCodes provider codes separated by '+', or null for every available provider
     * @param limit         number of headlines, 1-300
     */
    public List<NewsHeadline> getHeadlines(int conid, String providerCodes, int limit, String start, String end) {
        if (limit < 1 || limit > 300) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 300");
        }
        String providers = providerCodes;
        if (!StringUtils.hasText(providers)) {
            providers = getProviders().stream().map(NewsProviderDto::providerCode).collect(Collectors.joining("+"));
            if (providers.isEmpty()) {
                throw ApiException.notFound("NO_NEWS_PROVIDER", "No news provider is available for the account");
            }
        } else if (!PROVIDER_CODES.matcher(providers).matches()) {
            throw ApiException.badRequest("INVALID_PROVIDERS", "providers must be codes separated by '+', eg. BRFG+DJNL");
        }
        String providerList = providers;
        return requestRegistry.call(id -> connection.client().reqHistoricalNews(id, conid, providerList,
                start == null ? "" : start, end == null ? "" : end, limit, null));
    }

    public NewsArticle getArticle(String providerCode, String articleId) {
        List<NewsArticle> articles = requestRegistry.call(id -> connection.client()
                .reqNewsArticle(id, providerCode, articleId, null));
        if (articles.isEmpty()) {
            throw ApiException.notFound("ARTICLE_NOT_FOUND", "No article found");
        }
        NewsArticle article = articles.get(0);
        return new NewsArticle(providerCode, articleId, article.articleType(), article.text());
    }

    // -- callbacks

    public void onNewsProviders(NewsProvider[] providers) {
        int requestId = providersRequestId;
        for (NewsProvider provider : providers) {
            requestRegistry.addItem(requestId, new NewsProviderDto(provider.providerCode(), provider.providerName()));
        }
        requestRegistry.complete(requestId);
    }

    public void onHistoricalNews(int requestId, String time, String providerCode, String articleId, String headline) {
        requestRegistry.addItem(requestId, new NewsHeadline(time, providerCode, articleId, headline));
    }

    public void onHistoricalNewsEnd(int requestId) {
        requestRegistry.complete(requestId);
    }

    public void onNewsArticle(int requestId, int articleType, String articleText) {
        requestRegistry.addItem(requestId, new NewsArticle(null, null, articleType == 1 ? "binary" : "text",
                articleText));
        requestRegistry.complete(requestId);
    }
}
