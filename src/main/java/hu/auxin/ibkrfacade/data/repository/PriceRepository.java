package hu.auxin.ibkrfacade.data.repository;

import hu.auxin.ibkrfacade.data.PriceData;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceRepository extends CrudRepository<PriceData, Integer> {
}
