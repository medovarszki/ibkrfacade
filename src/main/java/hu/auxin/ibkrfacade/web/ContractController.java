package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.data.holder.Option;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.web.dto.ContractDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/**
 * Contract search, market data subscription and prices
 */
@Tag(name = "Contracts and market data")
@RestController
public class ContractController {

    private final ContractManagerService contractManagerService;

    ContractController(ContractManagerService contractManagerService) {
        this.contractManagerService = contractManagerService;
    }

    @Operation(summary = "Search for an instrument by it's ticker, or part of it's name.")
    @GetMapping("/search")
    List<ContractDto> searchContract(@Parameter(description = "Ticker, or name of the traded instrument", examples = {
            @ExampleObject(name = "Ticker", value = "AAPL"),
            @ExampleObject(name = "Company name", value = "Apple")
    }) @RequestParam String query) {
        return contractManagerService.searchContract(query).stream().map(ContractDto::of).toList();
    }

    @Operation(summary = "Subscribes to an instrument by it's conid. Subscription means the TWS starts streaming the "
            + "market data for the instrument which will be saved into Redis TimeSeries (stream:[conid]:[BID|ASK|LAST])")
    @GetMapping("/subscribe/{conid}")
    ContractDto subscribeMarketDataByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid) {
        contractManagerService.subscribeMarketData(conid);
        return ContractDto.of(contractManagerService.getContractHolder(conid));
    }

    @Operation(summary = "Returns the contract descriptor with its details. Unknown conids are resolved through TWS.")
    @GetMapping("/contract/{conid}")
    ContractDto getContractByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid) {
        return ContractDto.of(contractManagerService.getContractHolder(conid));
    }

    @Operation(summary = "Returns the last available bid, ask and last price by conid. The prices are null if there's "
            + "no market data subscription, or no tick arrived yet.")
    @GetMapping("/price/{conid}")
    MarketPrice getLastPriceByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid) {
        return contractManagerService.getLastPriceByConid(conid);
    }

    @Operation(summary = "Returns the last available bid, ask and last price for the given contract (only the conid is used).")
    @PostMapping("/price")
    MarketPrice getLastPriceByContract(@RequestBody ContractDto contract) {
        return contractManagerService.getLastPriceByConid(contract.conid());
    }

    @Operation(summary = "Returns the option chain for an underlying instrument. This method won't subscribe to the "
            + "market data of the options.")
    @GetMapping("/optionChain/{underlyingConid}")
    Collection<Option> getOptionChain(@Parameter(description = "The conid (IBKR unique id) of the underlying instrument") @PathVariable int underlyingConid) {
        return contractManagerService.getOptionChainByConid(underlyingConid);
    }
}
