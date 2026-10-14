package hu.auxin.ibkrfacade.news;

import com.ib.client.EClientSocket;
import com.ib.client.NewsProvider;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsServiceTest {

    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final NewsService service = new NewsService(connection, registry);

    @BeforeEach
    void setUp() {
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            service.onNewsProviders(new NewsProvider[]{new NewsProvider("BRFG", "Briefing.com General Market Columns"),
                    new NewsProvider("DJNL", "Dow Jones Newsletters")});
            return null;
        }).when(client).reqNewsProviders();
    }

    @Test
    void headlinesOfEveryProviderByDefault() {
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            service.onHistoricalNews(id, "2026-10-14 13:30:00.0", "BRFG", "BRFG$1", "{A:800015:L:en:K:n/a:C:0.97}Apple beats");
            service.onHistoricalNewsEnd(id);
            return null;
        }).when(client).reqHistoricalNews(anyInt(), eq(265598), eq("BRFG+DJNL"), eq(""), eq(""), eq(5), any());

        List<NewsHeadline> headlines = service.getHeadlines(265598, null, 5, null, null);

        assertThat(headlines).containsExactly(new NewsHeadline("2026-10-14 13:30:00.0", "BRFG", "BRFG$1", "Apple beats"));
        assertThat(service.getProviders()).extracting(NewsProviderDto::providerCode).containsExactly("BRFG", "DJNL");
    }

    @Test
    void articleAndMissingSubscription() {
        doAnswer(invocation -> {
            service.onNewsArticle(invocation.getArgument(0), 0, "Full text");
            return null;
        }).when(client).reqNewsArticle(anyInt(), eq("BRFG"), eq("BRFG$1"), any());
        assertThat(service.getArticle("BRFG", "BRFG$1"))
                .isEqualTo(new NewsArticle("BRFG", "BRFG$1", "text", "Full text"));

        doAnswer(invocation -> registry.fail(invocation.getArgument(0), new TwsException(10276, "News feed is not allowed")))
                .when(client).reqNewsArticle(anyInt(), eq("XYZ"), eq("1"), any());
        assertThatThrownBy(() -> service.getArticle("XYZ", "1")).isInstanceOf(TwsException.class);
    }
}
