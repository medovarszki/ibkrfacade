package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

@Schema(description = "Contract descriptor. It uses the conid of the Contract as a kind-of key.")
@Data
@NoArgsConstructor
@RedisHash("contract")
public class ContractHolder implements Serializable {

    /**
     * The internal unique identifier of every Contract can be accessed on Interactive Brokers
     */
    @Id
    private Integer conid;

    /**
     * The Contract descriptor itself
     */
    private Contract contract;

    /**
     * Details of the contract
     */
    private ContractDetails details;

    /**
     * Available options for the underlying asset. The system won't fill the option chain automatically,
     * you have to request for the available option chain first.
     *
     * @see hu.auxin.ibkrfacade.service.ContractManagerService#getOptionChainByConid(int)
     */
    private Set<Option> optionChain = new HashSet<>();

    /**
     * RequestId (or tickerId at some places in TWS API) of the market data subscription (if there's any) for the
     * contract in the current session. The time series of the contract are stored in Redis by the conid:
     * stream:[conid]:[BID|ASK|LAST]
     *
     * @see hu.auxin.ibkrfacade.data.TimeSeriesHandler
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
