package hu.auxin.ibkrfacade.data.repository;

import hu.auxin.ibkrfacade.data.ContractData;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRepository extends CrudRepository<ContractData, Integer> {
}
