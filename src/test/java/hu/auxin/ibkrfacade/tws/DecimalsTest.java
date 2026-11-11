package hu.auxin.ibkrfacade.tws;

import com.ib.client.Decimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalsTest {

    @Test
    void roundNumbersAreNotInExponentForm() {
        assertThat(Decimals.toBigDecimal(Decimal.get(520)).toString()).isEqualTo("520");
        assertThat(Decimals.toBigDecimal(Decimal.get(100)).toString()).isEqualTo("100");
        assertThat(Decimals.toBigDecimal(Decimal.parse("1.50")).toString()).isEqualTo("1.5");
        assertThat(Decimals.toBigDecimal(Decimal.get(0)).toString()).isEqualTo("0");
    }

    @Test
    void invalidIsNull() {
        assertThat(Decimals.toBigDecimal(null)).isNull();
        assertThat(Decimals.toBigDecimal(Decimal.INVALID)).isNull();
    }
}
