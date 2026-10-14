package hu.auxin.ibkrfacade.strategy;

import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.twssample.ConId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sample: subscribes to the market data of SPY, so its prices are streamed into Redis.
 * Disabled by default, enable it with ibkr.strategies.enabled=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ibkr.strategies.enabled", havingValue = "true")
public class SampleSaver {

    private final ContractManagerService contractManagerService;

    public SampleSaver(ContractManagerService contractManagerService) {
        this.contractManagerService = contractManagerService;
    }

    @EventListener
    public void onConnected(TwsConnectedEvent event) {
        if (!event.reconnect()) {
            log.info("Start sampling SPY");
            contractManagerService.subscribeMarketData(ConId.SPY);
        }
    }
}
