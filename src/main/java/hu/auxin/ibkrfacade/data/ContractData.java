package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@RedisHash("contract")
public class ContractData implements Serializable {

    @Id
    @Getter
    @Setter
    private Integer conid;

    @Getter
    @Setter
    private Contract contract;

    @Getter
    @Setter
    private Integer requestId;

    public ContractData(Contract contract) {
        this.conid = contract.conid();
        this.contract = contract;
    }
}
