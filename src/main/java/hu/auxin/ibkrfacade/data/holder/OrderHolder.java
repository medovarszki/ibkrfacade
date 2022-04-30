package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@AllArgsConstructor
public class OrderHolder {

    @Id
    private Integer permId;

    private Order order;

    private Contract contract;

    private OrderState orderState;
}
