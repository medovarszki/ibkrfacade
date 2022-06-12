package hu.auxin.ibkrfacade.data;

import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRepository extends CrudRepository<ContractHolder, Integer> {

    ContractHolder findContractHolderByOptionChainRequestId(Integer optionChainRequestId);

}
