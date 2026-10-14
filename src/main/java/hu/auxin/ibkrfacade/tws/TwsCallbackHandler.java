package hu.auxin.ibkrfacade.tws;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.ContractDescription;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.TickAttrib;
import com.ib.client.TickAttribBidAsk;
import com.ib.client.TickType;
import com.ib.client.Bar;
import com.ib.client.NewsProvider;
import hu.auxin.ibkrfacade.account.AccountValue;
import hu.auxin.ibkrfacade.data.holder.OptionParameters;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.news.NewsService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Receives the callbacks of the TWS API (on the TWS reader thread) and dispatches them to the pending requests
 * or to the services. Callbacks must never block, so the services can't wait for TWS responses here.
 * Every callback not overridden here is a no-op (see {@link DefaultEWrapper}).
 */
@Slf4j
@Component
public class TwsCallbackHandler extends DefaultEWrapper {

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final MarketDataService marketDataService;
    private final OrderManagerService orderManagerService;
    private final PositionManagerService positionManagerService;
    private final NewsService newsService;

    public TwsCallbackHandler(TwsConnection connection, TwsRequestRegistry requestRegistry,
                              MarketDataService marketDataService, OrderManagerService orderManagerService,
                              PositionManagerService positionManagerService, NewsService newsService) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.marketDataService = marketDataService;
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.newsService = newsService;
    }

    // -- connection

    @Override
    public void nextValidId(int orderId) {
        connection.onNextValidId(orderId);
    }

    @Override
    public void managedAccounts(String accountsList) {
        connection.onManagedAccounts(accountsList);
    }

    @Override
    public void connectionClosed() {
        connection.onConnectionClosed();
    }

    // -- market data

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        marketDataService.onTick(tickerId, field, price);
    }

    @Override
    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize,
                                 Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {
        marketDataService.onTick(reqId, TickType.BID.index(), bidPrice);
        marketDataService.onTick(reqId, TickType.ASK.index(), askPrice);
    }

    // -- contracts

    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        requestRegistry.addItem(reqId, contractDetails);
    }

    @Override
    public void contractDetailsEnd(int reqId) {
        requestRegistry.complete(reqId);
    }

    @Override
    public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
        for (ContractDescription description : contractDescriptions) {
            requestRegistry.addItem(reqId, description.contract());
        }
        requestRegistry.complete(reqId);
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId,
                                                    String tradingClass, String multiplier, Set<String> expirations,
                                                    Set<Double> strikes) {
        requestRegistry.addItem(reqId, new OptionParameters(exchange, tradingClass, multiplier, expirations, strikes));
    }

    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        requestRegistry.complete(reqId);
    }

    // -- orders

    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        orderManagerService.onOpenOrder(orderId, contract, order, orderState);
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice,
                            long permId, int parentId, double lastFillPrice, int clientId, String whyHeld,
                            double mktCapPrice) {
        orderManagerService.onOrderStatus(orderId, permId, status, filled, remaining, avgFillPrice);
    }

    @Override
    public void completedOrder(Contract contract, Order order, OrderState orderState) {
        orderManagerService.onCompletedOrder(contract, order, orderState);
    }

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        orderManagerService.onExecution(contract, execution);
    }

    @Override
    public void commissionAndFeesReport(CommissionAndFeesReport report) {
        orderManagerService.onCommissionReport(report);
    }

    // -- account and positions

    @Override
    public void position(String account, Contract contract, Decimal pos, double avgCost) {
        positionManagerService.onPosition(account, contract, pos, avgCost);
    }

    @Override
    public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue,
                                double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {
        positionManagerService.onPortfolio(accountName, contract, position, marketPrice, marketValue, averageCost,
                unrealizedPNL, realizedPNL);
    }

    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        requestRegistry.addItem(reqId, new AccountValue(account, tag, value, currency));
    }

    @Override
    public void accountSummaryEnd(int reqId) {
        requestRegistry.complete(reqId);
    }

    // -- historical data

    @Override
    public void historicalData(int reqId, Bar bar) {
        requestRegistry.addItem(reqId, bar);
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        requestRegistry.complete(reqId);
    }

    // -- news

    @Override
    public void newsProviders(NewsProvider[] newsProviders) {
        newsService.onNewsProviders(newsProviders);
    }

    @Override
    public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
        newsService.onHistoricalNews(requestId, time, providerCode, articleId, headline);
    }

    @Override
    public void historicalNewsEnd(int requestId, boolean hasMore) {
        newsService.onHistoricalNewsEnd(requestId);
    }

    @Override
    public void newsArticle(int requestId, int articleType, String articleText) {
        newsService.onNewsArticle(requestId, articleType, articleText);
    }

    // -- errors

    @Override
    public void error(Exception e) {
        log.error("TWS API error: {}", e.getMessage(), e);
    }

    @Override
    public void error(String str) {
        log.error("TWS API error: {}", str);
    }

    @Override
    public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (errorCode == TwsErrorCodes.CONNECTIVITY_LOST) {
            log.error("TWS lost its connectivity to the IB servers: {}", errorMsg);
            connection.onConnectivityLost();
            return;
        }
        if (errorCode == TwsErrorCodes.CONNECTIVITY_RESTORED_DATA_LOST
                || errorCode == TwsErrorCodes.CONNECTIVITY_RESTORED_DATA_MAINTAINED) {
            log.info("TWS connectivity restored: {}", errorMsg);
            connection.onConnectivityRestored(errorCode == TwsErrorCodes.CONNECTIVITY_RESTORED_DATA_LOST);
            return;
        }

        boolean warning = TwsErrorCodes.isWarning(errorCode);
        if (id > 0 && !TwsRequestRegistry.isRequestId(id)) {
            // order related message
            if (orderManagerService.onOrderError(id, errorCode, errorMsg, warning)) {
                return;
            }
        }
        if (warning) {
            log.info("TWS message (id={}, code={}): {}", id, errorCode, errorMsg);
            return;
        }

        log.warn("TWS error (id={}, code={}): {}", id, errorCode, errorMsg);
        if (id >= 0 && requestRegistry.fail(id, new TwsException(errorCode, errorMsg))) {
            return;
        }
        if (marketDataService.onStreamError(id, errorCode, errorMsg)) {
            return;
        }
        if (id < 0 || TwsErrorCodes.isConnectionError(errorCode)) {
            connection.onError(errorCode + ": " + errorMsg);
        }
    }
}
