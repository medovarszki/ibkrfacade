package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@RedisHash("contract")
@Data
public class ContractHolder implements Serializable {

    @Id
    private int conid;

    private Contract contract;

    /**
     * RequestId (or tickId at some places in TWS API) which identifies the data streams (if there's any) for the contract.
     * The key of a time series for a contract in Redis looks like the following: stream:[streamRequestId]:[BID|ASK]
     */
    private Integer streamRequestId;

    public ContractHolder(Contract contract) {
        this.conid = contract.conid();
        this.contract = contract;
    }
}
