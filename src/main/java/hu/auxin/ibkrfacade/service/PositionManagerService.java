package hu.auxin.ibkrfacade.service;

import com.ib.client.EClientSocket;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Scope;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Scope("singleton")
public class PositionManagerService {

    private static final Logger LOG = LogManager.getLogger(PositionManagerService.class);

    private Map<Integer, PositionHolder> positions = new HashMap<>();

    @NonNull
    private EClientSocket client;

    public void addPosition(PositionHolder positionHolder) {
        if(positionHolder.getQuantity() == 0) {
            positions.remove(positionHolder.getContract().conid());
        } else {
            positions.put(positionHolder.getContract().conid(), positionHolder);
        }
    }

    public Collection<PositionHolder> getPositions() {
        return positions.values();
    }

    public void setClient(EClientSocket client) {
        this.client = client;
    }
}
