package hu.auxin.ibkrfacade.data.redis;

import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRepository extends CrudRepository<ContractHolder, Integer> {
}
