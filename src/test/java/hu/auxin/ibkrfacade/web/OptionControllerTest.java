package hu.auxin.ibkrfacade.web;

import com.ib.client.Decimal;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.options.OptionChain;
import hu.auxin.ibkrfacade.options.OptionChainService;
import hu.auxin.ibkrfacade.options.OptionProduct;
import hu.auxin.ibkrfacade.tws.Decimals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OptionController.class)
class OptionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OptionChainService optionChainService;

    @Test
    void chainInSnakeCaseWithoutExponents() throws Exception {
        when(optionChainService.getChain(anyInt(), any(), anyDouble(), anyInt(), any())).thenReturn(chain());

        String body = mvc.perform(get("/options/chain").param("future_conid", "866999753"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.future_conid").value(866999753))
                .andExpect(jsonPath("$.expiration").value("20261215"))
                .andExpect(jsonPath("$.multiplier").value(100))
                .andExpect(jsonPath("$.trading_class").value("VIX"))
                .andExpect(jsonPath("$.underlying_price").value(21.35))
                .andExpect(jsonPath("$.options[0].conid").value(456))
                .andExpect(jsonPath("$.options[0].strike").value(20.0))
                .andExpect(jsonPath("$.options[0].right").value("C"))
                .andExpect(jsonPath("$.options[0].bid").value(2.15))
                .andExpect(jsonPath("$.options[0].quote_age_seconds").value(1))
                .andExpect(jsonPath("$.options[0].stale").value(false))
                .andExpect(jsonPath("$.options[0].model_iv").value(0.82))
                .andExpect(jsonPath("$.options[0].model_theta").value(-0.04))
                .andExpect(jsonPath("$.options[0].stream_error").isEmpty())
                .andExpect(jsonPath("$.options[1].model_delta").isEmpty())
                .andExpect(jsonPath("$.options[1].stream_error").value("354: Requested market data is not subscribed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"bid_size\":520,", "\"ask_size\":80,", "\"strike\":20.0,")
                .doesNotContain("E+", "520.0");
        assertThat(body).contains("\"stream_error\":null");
        // the defaults
        verify(optionChainService).getChain(866999753, OptionProduct.VIX, 0.25, 15,
                EnumSet.of(Types.Right.Call, Types.Right.Put));
    }

    @Test
    void parameters() throws Exception {
        when(optionChainService.getChain(anyInt(), any(), anyDouble(), anyInt(), any())).thenReturn(chain());

        mvc.perform(get("/options/chain").param("future_conid", "866999753").param("product", "VX_FOP")
                        .param("strike_range", "0.1").param("max_strikes", "5").param("rights", "P"))
                .andExpect(status().isOk());

        verify(optionChainService).getChain(866999753, OptionProduct.VX_FOP, 0.1, 5, EnumSet.of(Types.Right.Put));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(get("/options/chain")).andExpect(status().isBadRequest());
        mvc.perform(get("/options/chain").param("future_conid", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/options/chain").param("future_conid", "1").param("product", "SPX"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/options/chain").param("future_conid", "1").param("rights", "C,X"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/options/chain").param("future_conid", "1").param("max_strikes", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/options/chain").param("future_conid", "1").param("strike_range", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(optionChainService);
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        when(optionChainService.getChain(eq(1), any(), anyDouble(), anyInt(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FUTURE", "Conid 1 is not a future but STK"));

        mvc.perform(get("/options/chain").param("future_conid", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FUTURE"))
                .andExpect(jsonPath("$.detail").value("Conid 1 is not a future but STK"));
    }

    private static OptionChain chain() {
        return new OptionChain(866999753, "20261215", 100, "VIX", 21.35, List.of(
                new OptionChain.Quote(456, 20.0, "C", 2.15, 2.25, Decimals.toBigDecimal(Decimal.get(520)),
                        Decimals.toBigDecimal(Decimal.get(80)), 2.2, 1L, false, 0.82, 0.55, 0.09, 0.021, -0.04, null),
                new OptionChain.Quote(457, 20.0, "P", null, null, null, null, null, null, true, null, null, null, null,
                        null, "354: Requested market data is not subscribed")));
    }
}
