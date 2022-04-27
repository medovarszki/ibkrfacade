package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@AllArgsConstructor
@RedisHash("order")
public class OrderData {

    @Id
    @Getter
    private Integer permId;

    @Getter
    private Order order;

    @Getter
    private Contract contract;

    @Getter
    private OrderState state;
}
