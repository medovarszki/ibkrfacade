package hu.auxin.ibkrgateway;

import com.ib.client.*;
import hu.auxin.ibkrgateway.data.ContractData;
import hu.auxin.ibkrgateway.data.repository.ContractRepository;
import hu.auxin.ibkrgateway.twsapi.TwsReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Scope("singleton")
public class TwsClient {

    private static final Logger LOG = LogManager.getLogger(TwsClient.class);

    private static final Map<Integer, Object> RESULT = new HashMap<>();

    @Value("${ibkr.tws.host}")
    private String TWS_HOST;

    @Value("${ibkr.tws.port}")
    private int TWS_PORT;

    @Autowired
    private ContractRepository contractRepository;

    private EClientSocket client;
    private TwsReader wrapper;

    private static AtomicInteger autoIncrement = new AtomicInteger();

    private TwsClient() {}

    private void waitForResult(int reqId) {
        while(true) {
            if(RESULT.containsKey(reqId)) {
                return;
            }
        }
    }

    public List<Contract> searchContract(String search) {
        if(StringUtils.hasLength(search)) {
            LOG.debug("Searching for contracts: {}", search);
            int i = autoIncrement.getAndIncrement();
            client.reqMatchingSymbols(i, search);
            waitForResult(i);
            List<Contract> result = List.copyOf((List<Contract>) RESULT.get(i));
            RESULT.remove(i);
            return result;
        }
        return Collections.emptyList();
    }

    public ContractDetails requestContractDetails(Contract contract) {
        LOG.debug("Request contract details: {}", contract);
        int i = autoIncrement.getAndIncrement();
        client.reqContractDetails(i, contract);
        waitForResult(i);
        return (ContractDetails) RESULT.get(i);
    }

    public void subscribeToContract(Contract contract) {
        final int currentId = autoIncrement.getAndIncrement();
        Optional<ContractData> contractData = contractRepository.findById(contract.conid());
        if(contractData.isPresent()) {
            ContractData c = contractData.get();
            c.setStreamId(currentId);
            contractRepository.save(c);
        }
        client.reqMktData(currentId, contract, "", false, false, null);
    }

    public void connect() {
        wrapper = new TwsReader(RESULT);

        client = wrapper.getClient();
        final EReaderSignal signal = wrapper.getSignal();
        //! [connect]
        client.eConnect(TWS_HOST, TWS_PORT, 2);
        final EReader reader = new EReader(client, signal);
        reader.start();

        //An additional thread is created in this program design to empty the messaging queue
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    LOG.error(e);
                }
            }
        }).start();
    }
}
