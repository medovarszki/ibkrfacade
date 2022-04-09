package hu.auxin.ibkrgateway.data.repository;

import hu.auxin.ibkrgateway.data.PriceData;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceRepository extends CrudRepository<PriceData, Integer> {
}
