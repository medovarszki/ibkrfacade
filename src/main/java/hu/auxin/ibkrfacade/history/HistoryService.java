package hu.auxin.ibkrfacade.history;

import com.ib.client.Bar;
import com.ib.client.Contract;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.Decimals;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Historical bars through reqHistoricalData.
 *
 * @see <a href="https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#hist-bar-size">Valid bar sizes and durations</a>
 */
@Service
public class HistoryService {

    static final Set<String> BAR_SIZES = Set.of("1 secs", "5 secs", "10 secs", "15 secs", "30 secs", "1 min",
            "2 mins", "3 mins", "5 mins", "10 mins", "15 mins", "20 mins", "30 mins", "1 hour", "2 hours", "3 hours",
            "4 hours", "8 hours", "1 day", "1 week", "1 month");
    static final Set<String> WHAT_TO_SHOW = Set.of("TRADES", "MIDPOINT", "BID", "ASK", "BID_ASK", "ADJUSTED_LAST",
            "HISTORICAL_VOLATILITY", "OPTION_IMPLIED_VOLATILITY", "FEE_RATE", "SCHEDULE");
    private static final Pattern DURATION = Pattern.compile("[1-9][0-9]{0,4} [SDWMY]");
    private static final Pattern END_DATE_TIME = Pattern.compile("[0-9]{8}-[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TWS_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final DateTimeFormatter TWS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final ContractManagerService contractManagerService;
    private final Duration timeout;

    public HistoryService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                          ContractManagerService contractManagerService,
                          @Value("${ibkr.history.timeout:60s}") Duration timeout) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.contractManagerService = contractManagerService;
        this.timeout = timeout;
    }

    /**
     * @param endDateTime end of the period in UTC (yyyyMMdd-HH:mm:ss), null for now
     */
    public HistoryResponse getHistory(int conid, String duration, String barSize, String whatToShow, boolean useRth,
                                      String endDateTime) {
        validate(duration, barSize, whatToShow, endDateTime);
        ContractHolder holder = contractManagerService.getContractHolder(conid);
        Contract contract = holder.getContract();
        if (!StringUtils.hasText(contract.exchange())) {
            contract.exchange(StringUtils.hasText(contract.primaryExch()) ? contract.primaryExch() : "SMART");
        }
        String timeZoneId = holder.getDetails() != null ? holder.getDetails().timeZoneId() : null;
        ZoneId zone = zone(timeZoneId);

        List<Bar> bars = requestRegistry.call(id -> connection.client().reqHistoricalData(id, contract,
                endDateTime == null ? "" : endDateTime, duration, barSize, whatToShow, useRth ? 1 : 0, 2, false,
                null), timeout);

        return new HistoryResponse(conid, contract.symbol(), duration, barSize, whatToShow, useRth,
                StringUtils.hasText(timeZoneId) ? timeZoneId : zone.getId(),
                bars.stream().map(bar -> toBar(bar, zone)).toList());
    }

    static void validate(String duration, String barSize, String whatToShow, String endDateTime) {
        if (duration == null || !DURATION.matcher(duration).matches()) {
            throw ApiException.badRequest("INVALID_DURATION",
                    "duration must be '<number> <S|D|W|M|Y>', eg. '1 D' or '6 M'");
        }
        if (!BAR_SIZES.contains(barSize)) {
            throw ApiException.badRequest("INVALID_BAR_SIZE", "barSize must be one of " + BAR_SIZES);
        }
        if (!WHAT_TO_SHOW.contains(whatToShow)) {
            throw ApiException.badRequest("INVALID_WHAT_TO_SHOW", "whatToShow must be one of " + WHAT_TO_SHOW);
        }
        if (endDateTime != null && !END_DATE_TIME.matcher(endDateTime).matches()) {
            throw ApiException.badRequest("INVALID_END_DATE_TIME", "endDateTime must be yyyyMMdd-HH:mm:ss (UTC)");
        }
    }

    /**
     * With formatDate=2 TWS sends epoch seconds for intraday bars and yyyyMMdd for daily and longer bars.
     */
    static HistoricalBar toBar(Bar bar, ZoneId zone) {
        String time = bar.time().trim();
        String isoTime;
        long timestamp;
        if (time.length() == 8 && time.chars().allMatch(Character::isDigit)) {
            LocalDate date = LocalDate.parse(time, TWS_DATE);
            isoTime = date.toString();
            timestamp = date.atStartOfDay(zone).toInstant().toEpochMilli();
        } else if (time.chars().allMatch(Character::isDigit)) {
            ZonedDateTime dateTime = Instant.ofEpochSecond(Long.parseLong(time)).atZone(zone);
            isoTime = dateTime.toOffsetDateTime().toString();
            timestamp = dateTime.toInstant().toEpochMilli();
        } else {
            // yyyyMMdd HH:mm:ss [zone]
            String[] parts = time.split(" ");
            ZoneId barZone = parts.length > 2 ? zone(parts[2]) : zone;
            ZonedDateTime dateTime = LocalDateTime.parse(parts[0] + " " + parts[1], TWS_DATE_TIME).atZone(barZone)
                    .withZoneSameInstant(zone);
            isoTime = dateTime.toOffsetDateTime().toString();
            timestamp = dateTime.toInstant().toEpochMilli();
        }
        return new HistoricalBar(isoTime, timestamp, bar.open(), bar.high(), bar.low(), bar.close(),
                Decimals.toBigDecimal(bar.volume()), Decimals.toBigDecimal(bar.wap()), bar.count());
    }

    static ZoneId zone(String timeZoneId) {
        if (!StringUtils.hasText(timeZoneId)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZoneId.trim(), ZoneId.SHORT_IDS);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
