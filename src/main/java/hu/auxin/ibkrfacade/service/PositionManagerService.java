package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@Scope("singleton")
public class PositionManagerService {

    private final Map<Integer, PositionHolder> positions = new HashMap<>();

    public void addPosition(PositionHolder positionHolder) {
        if(positionHolder.getQuantity() == 0) {
            positions.remove(positionHolder.getContract().conid());
        } else {
            positions.put(positionHolder.getContract().conid(), positionHolder);
        }
    }

    public Collection<PositionHolder> getAllPositions() {
        return positions.values();
    }

    public PositionHolder getPositionByContract(Contract contract) {
        return positions.get(contract.conid());
    }

    public PositionHolder getPositionByConid(int conid) {
        return positions.get(conid);
    }
}
