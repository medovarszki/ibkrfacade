package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PositionHolder {

    private Contract contract;

    private double quantity;

    private double avgPrice;
}
