package hu.auxin.ibkrfacade.marketdata;

import com.ib.client.Contract;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Calendar spread of two futures: the spread computed from the quotes of the legs, next to the quote of the
 * exchange listed spread.
 */
@Service
public class SpreadService {

    private final ContractManagerService contractManagerService;
    private final ComboMarketDataService comboMarketDataService;

    public SpreadService(ContractManagerService contractManagerService,
                         ComboMarketDataService comboMarketDataService) {
        this.contractManagerService = contractManagerService;
        this.comboMarketDataService = comboMarketDataService;
    }

    public SpreadQuote getSpread(int nearConid, int farConid) {
        if (nearConid == farConid) {
            throw ApiException.badRequest("INVALID_SPREAD", "The two legs of the spread must differ");
        }
        Contract near = contractManagerService.getContractHolder(nearConid).getContract();
        Contract far = contractManagerService.getContractHolder(farConid).getContract();
        if (near.secType() != Types.SecType.FUT || far.secType() != Types.SecType.FUT) {
            throw ApiException.badRequest("INVALID_SPREAD", "Both legs of the spread must be futures");
        }
        if (!Objects.equals(near.symbol(), far.symbol()) || !Objects.equals(near.exchange(), far.exchange())
                || !Objects.equals(near.currency(), far.currency())) {
            throw ApiException.badRequest("INVALID_SPREAD",
                    "The legs must be futures of the same underlying on the same exchange");
        }
        List<MarketPrice> legs = contractManagerService.getLastPrices(List.of(nearConid, farConid));
        SpreadQuote.Native nativeQuote;
        try {
            nativeQuote = comboMarketDataService.quote(near, far);
        } catch (TwsNotConnectedException e) {
            nativeQuote = SpreadQuote.Native.unavailable("TWS is not connected");
        }
        return new SpreadQuote(nearConid, farConid, legs.get(0), legs.get(1),
                SpreadQuote.Synthetic.of(legs.get(0), legs.get(1)), nativeQuote);
    }
}
