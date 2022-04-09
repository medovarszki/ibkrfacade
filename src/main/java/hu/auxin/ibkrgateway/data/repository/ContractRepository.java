package hu.auxin.ibkrgateway.data.repository;

import hu.auxin.ibkrgateway.data.ContractData;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRepository extends CrudRepository<ContractData, Integer> {
}
