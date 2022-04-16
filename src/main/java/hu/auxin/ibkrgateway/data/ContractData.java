package hu.auxin.ibkrgateway.data;

import com.ib.client.Contract;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@RedisHash("contract")
public class ContractData implements Serializable {

    @Id
    private Integer conid;

    private Contract contract;

    private Integer requestId;

    public ContractData(Contract contract) {
        this.conid = contract.conid();
        this.contract = contract;
    }

    public Integer getConid() {
        return conid;
    }

    public void setConid(Integer conid) {
        this.conid = conid;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public Integer getRequestId() {
        return requestId;
    }

    public void setRequestId(Integer requestId) {
        this.requestId = requestId;
    }
}
