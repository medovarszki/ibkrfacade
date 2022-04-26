package hu.auxin.ibkrfacade.data.redis;

import hu.auxin.ibkrfacade.data.OrderData;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends CrudRepository<OrderData, Integer> {
}
