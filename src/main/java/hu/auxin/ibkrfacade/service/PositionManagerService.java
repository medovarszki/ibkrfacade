package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.tws.Decimals;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open positions of the managed accounts (reqPositions), enriched with the market value and P&L of the
 * portfolio updates (reqAccountUpdates).
 */
@Slf4j
@Service
public class PositionManagerService {

    private final TwsConnection connection;

    /**
     * Account of the portfolio updates (market value and P&L), the first managed account if empty
     */
    private final String portfolioAccount;

    private final Map<String, PositionHolder> positions = new ConcurrentHashMap<>();

    public PositionManagerService(TwsConnection connection, @Value("${ibkr.account:}") String portfolioAccount) {
        this.connection = connection;
        this.portfolioAccount = portfolioAccount;
    }

    @EventListener
    public void onConnected(TwsConnectedEvent event) throws InterruptedException {
        positions.clear();
        connection.client().reqPositions();

        // the managed accounts arrive right after the connection
        for (int i = 0; i < 50 && connection.getAccounts().isEmpty(); i++) {
            Thread.sleep(100);
        }
        String account = StringUtils.hasText(portfolioAccount) ? portfolioAccount
                : connection.getAccounts().stream().findFirst().orElse(null);
        if (account == null) {
            log.warn("No managed account, portfolio updates (market value, P&L) are not requested");
            return;
        }
        connection.client().reqAccountUpdates(true, account);
        log.info("Subscribed to the portfolio updates of {}", TwsConnection.maskAccount(account));
    }

    /**
     * Portfolio update (reqAccountUpdates): position with market price, market value and P&L
     */
    public void onPortfolio(String account, Contract contract, Decimal pos, double marketPrice, double marketValue,
                            double averageCost, double unrealizedPnl, double realizedPnl) {
        BigDecimal quantity = Decimals.toBigDecimal(pos);
        String key = key(account, contract.conid());
        if (quantity == null || quantity.signum() == 0) {
            positions.remove(key);
            return;
        }
        positions.put(key, new PositionHolder(account, contract.conid(), contract.symbol(), contract.getSecType(),
                contract.currency(), quantity, averageCost, Decimals.orNull(marketPrice), Decimals.orNull(marketValue),
                Decimals.orNull(unrealizedPnl), Decimals.orNull(realizedPnl), Instant.now()));
    }

    public void onPosition(String account, Contract contract, Decimal pos, double avgCost) {
        BigDecimal quantity = Decimals.toBigDecimal(pos);
        String key = key(account, contract.conid());
        if (quantity == null || quantity.signum() == 0) {
            positions.remove(key);
            return;
        }
        positions.compute(key, (k, previous) -> new PositionHolder(account, contract.conid(), contract.symbol(),
                contract.getSecType(), contract.currency(), quantity, avgCost,
                previous == null ? null : previous.marketPrice(),
                previous == null ? null : previous.marketValue(),
                previous == null ? null : previous.unrealizedPnl(),
                previous == null ? null : previous.realizedPnl(),
                Instant.now()));
    }

    public Collection<PositionHolder> getAllPositions() {
        return positions.values().stream()
                .sorted(Comparator.comparing(PositionHolder::account).thenComparing(PositionHolder::conid))
                .toList();
    }

    public List<PositionHolder> getPositionsByConid(int conid) {
        return positions.values().stream().filter(position -> position.conid() == conid).toList();
    }

    /**
     * Position of the instrument, summed up for all accounts, or null if there's no open position.
     */
    public BigDecimal getQuantityByConid(int conid) {
        return getPositionsByConid(conid).stream().map(PositionHolder::quantity)
                .reduce(BigDecimal::add).orElse(null);
    }

    private static String key(String account, int conid) {
        return account + "|" + conid;
    }
}
