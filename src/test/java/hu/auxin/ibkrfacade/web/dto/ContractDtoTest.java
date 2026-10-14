package hu.auxin.ibkrfacade.web.dto;

import com.ib.client.Contract;
import com.ib.client.Types;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDtoTest {

    @Test
    void unsetStrikeIsNull() {
        Contract stock = new Contract();
        stock.conid(265598);
        stock.secType(Types.SecType.STK);
        stock.strike(Double.MAX_VALUE);
        assertThat(ContractDto.of(stock).strike()).isNull();

        stock.strike(0);
        assertThat(ContractDto.of(stock).strike()).isNull();

        Contract option = new Contract();
        option.secType(Types.SecType.OPT);
        option.strike(250);
        assertThat(ContractDto.of(option).strike()).isEqualTo(250.0);
    }
}
