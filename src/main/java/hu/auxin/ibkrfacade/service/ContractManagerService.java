package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.redislabs.redistimeseries.Range;
import hu.auxin.ibkrfacade.TWS;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("singleton")
public class ContractManagerService {

    private TWS tws;

    private final TimeSeriesHandler timeSeriesHandler;
    private final ContractRepository contractRepository;

    ContractManagerService(@Autowired TWS tws,
                           @Autowired TimeSeriesHandler timeSeriesHandler,
                           @Autowired ContractRepository contractRepository) {
        this.tws = tws;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    public List<Contract> searchContract(String search) {
        return tws.searchContract(search);
    }

    public Contract getContractByConid(int conid) {
        return tws.getContractByConid(conid);
    }

    public void subscribeMarketData(Contract contract) {
        subscribeMarketData(contract, false);
    }

    public void subscribeMarketData(Contract contract, boolean tickByTick) {
        tws.subscribeMarketData(contract, tickByTick);
    }

    public Range[] getTimeSeriesRangesByContract(Contract contract) {
        int streamReqId = contractRepository.findById(contract.conid()).get().getStreamRequestId();
        return timeSeriesHandler.getInstance().mget(true, "stream:" + streamReqId);
    }

    public PriceHolder getLastPriceByContract(int conid) {
        ContractHolder contractHolder = contractRepository.findById(conid).orElseThrow(() -> new RuntimeException("No conid found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    public PriceHolder getLastPriceByContract(Contract contract) {
        ContractHolder contractHolder = contractRepository.findById(contract.conid()).orElseThrow(() -> new RuntimeException("No Contract found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    public PriceHolder getLastPriceByContractHolder(ContractHolder contractHolder) {
        double bid = timeSeriesHandler.getInstance().get("stream:" + contractHolder.getStreamRequestId() + ":" + TickType.BID).getValue();
        double ask = timeSeriesHandler.getInstance().get("stream:" + contractHolder.getStreamRequestId() + ":" + TickType.ASK).getValue();
        return new PriceHolder(bid, ask);
    }
}
