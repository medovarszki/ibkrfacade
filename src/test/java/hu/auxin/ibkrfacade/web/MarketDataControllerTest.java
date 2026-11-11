package hu.auxin.ibkrfacade.web;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataType;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.marketdata.MarketStats;
import hu.auxin.ibkrfacade.marketdata.SpreadQuote;
import hu.auxin.ibkrfacade.marketdata.SpreadService;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketDataController.class)
class MarketDataControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractManagerService contractManagerService;

    @MockitoBean
    private SpreadService spreadService;

    @Test
    void futuresChain() throws Exception {
        Contract contract = new Contract();
        contract.conid(1001);
        contract.symbol("VIX");
        contract.secType(Types.SecType.FUT);
        contract.localSymbol("VXX6");
        contract.tradingClass("VX");
        contract.lastTradeDateOrContractMonth("20261118");
        ContractDetails details = new ContractDetails();
        details.contractMonth("202611");
        details.realExpirationDate("20261118");
        details.underConid(13455763);
        when(contractManagerService.getFuturesChain("VIX", "CFE", null, "VX", false))
                .thenReturn(List.of(new ContractHolder(contract, details)));

        mvc.perform(get("/futures/chain").param("symbol", "VIX").param("exchange", "CFE").param("trading_class", "VX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conid").value(1001))
                .andExpect(jsonPath("$[0].local_symbol").value("VXX6"))
                .andExpect(jsonPath("$[0].last_trade_date").value("20261118"))
                .andExpect(jsonPath("$[0].contract_month").value("202611"))
                .andExpect(jsonPath("$[0].real_expiration_date").value("20261118"))
                .andExpect(jsonPath("$[0].underlying_conid").value(13455763));
    }

    @Test
    void prices() throws Exception {
        when(contractManagerService.getLastPrices(List.of(1001, 1002))).thenReturn(List.of(price(1001, 17.05, 17.10),
                price(1002, 18.35, 18.40)));

        mvc.perform(get("/prices").param("conids", "1001,1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conid").value(1001))
                .andExpect(jsonPath("$[0].bid_size").value(142))
                .andExpect(jsonPath("$[0].open_interest").value(96400))
                .andExpect(jsonPath("$[0].close").value(17.35))
                .andExpect(jsonPath("$[0].mid").value(17.075))
                .andExpect(jsonPath("$[0].spread").value(0.05))
                .andExpect(jsonPath("$[0].quote_basis").value("BID_ASK"))
                .andExpect(jsonPath("$[1].bid").value(18.35));
    }

    @Test
    void conidsAreValidated() throws Exception {
        mvc.perform(get("/prices").param("conids", "1001,-2")).andExpect(status().isBadRequest());
        mvc.perform(get("/prices")).andExpect(status().isBadRequest());
        verifyNoInteractions(contractManagerService);
    }

    @Test
    void spread() throws Exception {
        MarketPrice near = price(1001, 17.05, 17.10);
        MarketPrice far = price(1002, 18.35, 18.40);
        when(spreadService.getSpread(1001, 1002)).thenReturn(new SpreadQuote(1001, 1002, near, far,
                SpreadQuote.Synthetic.of(near, far), SpreadQuote.Native.unavailable("TWS is not connected")));

        mvc.perform(get("/spread").param("near", "1001").param("far", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.near_conid").value(1001))
                .andExpect(jsonPath("$.far.bid").value(18.35))
                .andExpect(jsonPath("$.synthetic.bid").value(1.25))
                .andExpect(jsonPath("$.synthetic.ask").value(1.35))
                .andExpect(jsonPath("$.native.subscribed").value(false))
                .andExpect(jsonPath("$.native.stream_error").value("TWS is not connected"));
    }

    private static MarketPrice price(int conid, double bid, double ask) {
        Instant now = Instant.now();
        return MarketPrice.of(conid, bid, ask, bid, now, now, now, true, null, MarketDataType.LIVE, now,
                Duration.ofMinutes(15), MarketPrice.QuoteBasis.BID_ASK, new MarketStats(new BigDecimal("142"),
                        new BigDecimal("98"), null, new BigDecimal("48210"), new BigDecimal("96400"), 17.35, null,
                        null, null));
    }
}
