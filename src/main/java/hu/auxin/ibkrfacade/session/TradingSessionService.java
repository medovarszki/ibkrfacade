package hu.auxin.ibkrfacade.session;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsTimeoutException;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Open / next open / next close of an instrument from the trading hours of its contract details. The contract
 * details are cached in Redis, but TWS sends the hours only about one week ahead, so they are requested again when
 * the cached hours run out.
 */
@Slf4j
@Service
public class TradingSessionService {

    /**
     * The cached hours are refreshed if they don't reach this many days after today (exchange time zone)
     */
    static final int MIN_DAYS_AHEAD = 2;

    private final ContractManagerService contractManagerService;
    private final ContractRepository contractRepository;
    private final Clock clock;

    @Autowired
    TradingSessionService(ContractManagerService contractManagerService, ContractRepository contractRepository) {
        this(contractManagerService, contractRepository, Clock.systemUTC());
    }

    TradingSessionService(ContractManagerService contractManagerService, ContractRepository contractRepository,
                          Clock clock) {
        this.contractManagerService = contractManagerService;
        this.contractRepository = contractRepository;
        this.clock = clock;
    }

    /**
     * @param extended false: liquid_hours (regular trading hours), true: trading_hours (with the extended hours)
     */
    public TradingSession getSession(int conid, boolean extended) {
        Instant now = clock.instant();
        ContractHolder holder = contractManagerService.getContractHolder(conid);
        if (!covers(holder.getDetails(), extended, now)) {
            holder = refresh(holder);
        }
        ContractDetails details = holder.getDetails();
        if (details == null || details.timeZoneId() == null || details.timeZoneId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TRADING_HOURS_UNAVAILABLE",
                    "TWS sent no trading hours for conid " + conid);
        }
        ZoneId zone = zone(details.timeZoneId());
        TradingHours hours = TradingHours.parse(hours(details, extended), zone);
        Optional<TradingHours.Session> current = hours.current(now);
        Optional<TradingHours.Session> next = hours.next(now);
        Instant nextClose = current.or(() -> next).map(TradingHours.Session::close).orElse(null);
        return new TradingSession(conid, extended ? "trading" : "liquid", details.timeZoneId(), current.isPresent(),
                next.map(TradingHours.Session::open).orElse(null), nextClose, now);
    }

    private static boolean covers(ContractDetails details, boolean extended, Instant now) {
        if (details == null || details.timeZoneId() == null || details.timeZoneId().isBlank()) {
            return false;
        }
        ZoneId zone = zone(details.timeZoneId());
        LocalDate lastDate = TradingHours.parse(hours(details, extended), zone).lastDate();
        return lastDate != null && !lastDate.isBefore(now.atZone(zone).toLocalDate().plusDays(MIN_DAYS_AHEAD));
    }

    private ContractHolder refresh(ContractHolder holder) {
        Contract contract = new Contract();
        contract.conid(holder.getConid());
        try {
            holder.setDetails(contractManagerService.getContractDetails(contract));
            contractRepository.save(holder);
        } catch (TwsNotConnectedException | TwsTimeoutException e) {
            log.warn("Could not refresh the trading hours of conid {}, using the cached ones: {}", holder.getConid(),
                    e.getMessage());
        }
        return holder;
    }

    private static String hours(ContractDetails details, boolean extended) {
        return extended ? details.tradingHours() : details.liquidHours();
    }

    private static ZoneId zone(String timeZoneId) {
        try {
            return ZoneId.of(timeZoneId.trim());
        } catch (DateTimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TRADING_HOURS_UNAVAILABLE",
                    "Unknown time zone of the exchange: " + timeZoneId);
        }
    }
}
