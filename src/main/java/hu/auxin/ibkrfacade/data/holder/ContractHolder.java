package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@Schema(description = "Contract descriptor. It uses the conid of the Contract as a kind-of key.")
@Data
@NoArgsConstructor
@RedisHash("contract")
public class ContractHolder implements Serializable {

    /**
     * The internal unique identifier of every Contract can be accessed on Interactive Brokers
     */
    @Id
    private int conid;

    /**
     * The Contract descriptor itself
     */
    private Contract contract;

    /**
     * Details of the contract
     */
    private ContractDetails details;

    /**
     * RequestId (or tickId at some places in TWS API) which identifies the data streams (if there's any) for the contract.
     * The key of a time series for a contract in Redis looks like the following: stream:[streamRequestId]:[BID|ASK]
     */
    private Integer streamRequestId;

    public ContractHolder(Contract contract) {
        this.conid = contract.conid();
        this.contract = contract;
    }

    public ContractHolder(Contract contract, ContractDetails details) {
        this(contract);
        this.details = details;
    }
}
