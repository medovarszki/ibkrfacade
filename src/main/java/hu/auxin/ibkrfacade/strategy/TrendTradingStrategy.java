package hu.auxin.ibkrfacade.strategy;

import com.ib.client.TickType;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.order.OrderKind;
import hu.auxin.ibkrfacade.order.OrderService;
import hu.auxin.ibkrfacade.order.OrderSide;
import hu.auxin.ibkrfacade.order.PlaceOrderRequest;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.twssample.ConId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import redis.clients.jedis.timeseries.TSElement;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sample trading strategy, don't even think about using it real conditions!
 * <p>
 * Disabled by default, enable it with ibkr.strategies.enabled=true. Even then the trading methods are not scheduled,
 * uncomment the @Scheduled annotations to make it trade. Orders go through the same safety checks (paper/live guard,
 * market price check) as the REST API.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ibkr.strategies.enabled", havingValue = "true")
public class TrendTradingStrategy {

    private static final int APPLE = ConId.AAPL;

    private final TimeSeriesHandler timeSeriesHandler;
    private final OrderService orderService;
    private final OrderManagerService orderManagerService;
    private final PositionManagerService positionManagerService;
    private final ContractManagerService contractManagerService;

    public TrendTradingStrategy(OrderService orderService, OrderManagerService orderManagerService,
                                PositionManagerService positionManagerService, TimeSeriesHandler timeSeriesHandler,
                                ContractManagerService contractManagerService) {
        this.orderService = orderService;
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.contractManagerService = contractManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
    }

    @EventListener
    public void onConnected(TwsConnectedEvent event) {
        if (!event.reconnect()) {
            contractManagerService.subscribeMarketData(APPLE);
        }
    }

    //    @Scheduled(cron = "0/10 * 9-16 * * ?", zone = "America/New_York") // job will run every 10 seconds (0/10) during the hours of 9am to 4pm
    private synchronized void checkForTradingSignal() {
        if (!orderManagerService.getActiveOrdersByConid(APPLE).isEmpty()) {
            log.info("We already have an open order");
        } else if (positionManagerService.getQuantityByConid(APPLE) != null) {
            log.info("We already have an open position");
        } else {
            long now = System.currentTimeMillis();
            long sampleLength = 60 * 1000; // take 1 minutes of data

            try {
                List<TSElement> bidArray = timeSeriesHandler.getInstance()
                        .tsRange(TimeSeriesHandler.key(APPLE, TickType.BID), now - sampleLength, now);

                TSElement firstTsElement = bidArray.get(0);
                TSElement lastTsElement = bidArray.get(bidArray.size() - 1);
                if (bidArray.size() > 10 && firstTsElement.getTimestamp() > now - sampleLength) {
                    double changePercentage = ((lastTsElement.getValue() / firstTsElement.getValue()) - 1) * 100;
                    log.info("Change in time window: {}%", changePercentage);
                    if (Math.abs(changePercentage) > 0.05) { // rate of change in selected time window must be at least
                        log.info("BUY ORDER");
                        place(OrderSide.BUY, BigDecimal.TEN, OrderKind.MKT, null, null);
                    }
                }
            } catch (Exception e) {
                log.error("no data for Apple as yet", e);
            }
        }
    }

    //    @Scheduled(fixedRate = 5, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    private synchronized void handleClose() {
        List<PositionHolder> positions = positionManagerService.getPositionsByConid(APPLE);
        if (positions.isEmpty()) {
            return;
        }
        PositionHolder position = positions.get(0);
        boolean isShort = position.quantity().signum() < 0;
        MarketPrice lastPrice = contractManagerService.getLastPriceByConid(APPLE);
        if (lastPrice.bid() == null) {
            return;
        }

        double maxChange = position.avgCost() * 0.01; // max allowed loss
        double stopLoss = isShort ? position.avgCost() + maxChange : position.avgCost() - maxChange;
        OrderSide closingSide = isShort ? OrderSide.BUY : OrderSide.SELL;

        if (orderManagerService.getActiveOrdersByConid(APPLE).isEmpty()) {
            if (lastPrice.bid() < stopLoss) {
                // close position on market price, when threshold reached
                place(closingSide, position.quantity().abs(), OrderKind.MKT, null, null);
            } else if (lastPrice.bid() > position.avgCost() + maxChange) {
                // we have some unrealized gains, set a stop loss
                double activationPrice = lastPrice.bid() - stopLoss;
                double limitPrice = lastPrice.bid() - stopLoss - 0.5;
                place(closingSide, position.quantity().abs(), OrderKind.STP_LMT, limitPrice, activationPrice);
            }
        }
    }

    private void place(OrderSide side, BigDecimal quantity, OrderKind kind, Double limitPrice, Double stopPrice) {
        orderService.place(new PlaceOrderRequest("trend-" + UUID.randomUUID(), APPLE, side, quantity, kind,
                limitPrice, stopPrice, null, null, null));
    }
}
