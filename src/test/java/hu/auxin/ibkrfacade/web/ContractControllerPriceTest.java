package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.marketdata.MarketDataType;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractController.class)
class ContractControllerPriceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractManagerService contractManagerService;

    @Test
    void priceContainsTheFreshnessFields() throws Exception {
        Instant time = Instant.now().minus(Duration.ofHours(20));
        when(contractManagerService.getLastPriceByConid(eq(265598), isNull())).thenReturn(MarketPrice.of(265598,
                345.0, 345.01, 345.0016, time, time, time, true, null, MarketDataType.FROZEN, Instant.now(),
                Duration.ofMinutes(15)));

        mvc.perform(get("/price/265598"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bid").value(345.0))
                .andExpect(jsonPath("$.quote_time").exists())
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.stream_error").isEmpty())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.quote_age_seconds").value(72000))
                .andExpect(jsonPath("$.market_data_type").value("FROZEN"));
    }

    @Test
    void stalePriceIsAProblemDetailWithMaxAge() throws Exception {
        when(contractManagerService.getLastPriceByConid(265598, Duration.ofSeconds(60)))
                .thenThrow(ApiException.unprocessable("STALE_MARKET_DATA", "No bid/ask for conid 265598"));

        mvc.perform(get("/price/265598").param("max_age", "60"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STALE_MARKET_DATA"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void maxAgeMustBePositive() throws Exception {
        mvc.perform(get("/price/265598").param("max_age", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(contractManagerService);
    }
}
