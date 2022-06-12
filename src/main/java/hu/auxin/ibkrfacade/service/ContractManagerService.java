package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.TickType;
import hu.auxin.ibkrfacade.TWS;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Contract related operations. The service acts as a bridge between the TWS itself and the other parts of the system,
 * eg. the REST API or the strategy implementations.
 */
@Service
@Scope("singleton")
public class ContractManagerService {

    private TWS tws;

    private final TimeSeriesHandler timeSeriesHandler;
    private final ContractRepository contractRepository;

    @Autowired
    ContractManagerService(TWS tws, TimeSeriesHandler timeSeriesHandler, ContractRepository contractRepository) {
        this.tws = tws;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    public List<Contract> searchContract(String search) {
        return tws.searchContract(search);
    }

    public Contract getContractByConid(int conid) {
        Optional<ContractHolder> contractHolderInRedis = contractRepository.findById(conid);
        return contractHolderInRedis.isPresent() ? contractHolderInRedis.get().getContract() : tws.requestContractByConid(conid);
    }

    public ContractDetails getContractDetails(Contract contract) {
        return tws.requestContractDetails(contract);
    }

    public void subscribeMarketData(Contract contract) {
        subscribeMarketData(contract, false);
    }

    public void subscribeMarketData(Contract contract, boolean tickByTick) {
        tws.subscribeMarketData(contract, tickByTick);
    }

    /**
     * Returns the latest available ask and bid price for the given conid
     * @param conid
     * @return
     */
    public PriceHolder getLastPriceByConid(int conid) {
        ContractHolder contractHolder = contractRepository.findById(conid).orElseThrow(() -> new RuntimeException("No conid found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    /**
     * Returns the latest available ask and bid price for the given Contract
     * @param contract
     * @return
     */
    public PriceHolder getLastPriceByContract(Contract contract) {
        ContractHolder contractHolder = contractRepository.findById(contract.conid()).orElseThrow(() -> new RuntimeException("No Contract found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    /**
     * Getting the option chain for an underlying instrument
     * @param underlyingConid
     * @return
     */
    public List<ContractHolder> getOptionChainByConid(int underlyingConid) {
        Contract underlying = getContractByConid(underlyingConid);
        return tws.requestForOptionChain(underlying).stream().map(this::getContractHolder).collect(Collectors.toList());
    }

    /**
     * Returns the latest available ask and bid price for the given ContractHolder
     * @param contractHolder
     * @return
     */
    public PriceHolder getLastPriceByContractHolder(ContractHolder contractHolder) {
        double bid = timeSeriesHandler.getInstance().get("stream:" + contractHolder.getStreamRequestId() + ":" + TickType.BID).getValue();
        double ask = timeSeriesHandler.getInstance().get("stream:" + contractHolder.getStreamRequestId() + ":" + TickType.ASK).getValue();
        return new PriceHolder(bid, ask);
    }

    /**
     * Checking in Redis if the requested Contract already has a data stream.
     * If so, return with the already stored ContractHolder instead of creating a new one.
     * @param contract
     * @return
     */
    public ContractHolder getContractHolder(Contract contract) {
        return contractRepository.findById(contract.conid()).orElse(new ContractHolder(contract));
    }

    /**
     * Checking in Redis if the requested Contract already has a data stream.
     * If so, return with the already stored ContractHolder instead of creating a new one.
     * @param conid
     * @return
     */
    public ContractHolder getContractHolder(int conid) {
        return new ContractHolder(getContractByConid(conid));
    }
}
