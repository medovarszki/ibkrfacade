package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.TickType;
import hu.auxin.ibkrfacade.TWS;
import hu.auxin.ibkrfacade.TwsResultHolder;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.Option;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import redis.clients.jedis.timeseries.TSElement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Contract related operations. The service acts as a bridge between the TWS
 * itself and the other parts of the system,
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
        TwsResultHolder resultHolder = tws.searchContract(search);
        if (StringUtils.hasLength(resultHolder.getError())) {
            throw new RuntimeException();
        }
        return (List<Contract>) resultHolder.getResult();
    }

    public ContractDetails getContractDetails(Contract contract) {
        TwsResultHolder resultHolder = tws.requestContractDetails(contract);
        if (StringUtils.hasLength(resultHolder.getError())) {
            throw new RuntimeException();
        }
        return (ContractDetails) resultHolder.getResult();
    }

    public int subscribeMarketData(Contract contract) {
        return subscribeMarketData(contract, false);
    }

    public int subscribeMarketData(Contract contract, boolean tickByTick) {
        return tws.subscribeMarketData(contract, tickByTick);
    }

    /**
     * Returns the latest available ask and bid price for the given conid
     * 
     * @param conid
     * @return
     */
    public PriceHolder getLastPriceByConid(int conid) {
        ContractHolder contractHolder = contractRepository.findById(conid)
                .orElseThrow(() -> new RuntimeException("No conid found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    /**
     * Returns the latest available ask and bid price for the given Contract
     * 
     * @param contract
     * @return
     */
    public PriceHolder getLastPriceByContract(Contract contract) {
        ContractHolder contractHolder = contractRepository.findById(contract.conid())
                .orElseThrow(() -> new RuntimeException("No Contract found"));
        return getLastPriceByContractHolder(contractHolder);
    }

    /**
     * Getting the option chain for an underlying instrument
     * 
     * @param underlyingConid
     * @return
     */
    public Collection<Option> getOptionChainByConid(int underlyingConid) {
        ContractHolder underlying = getContractHolder(underlyingConid);
        return tws.requestForOptionChain(underlying.getContract());
    }

    /**
     * Returns the latest available ask and bid price for the given ContractHolder
     * 
     * @param contractHolder
     * @return
     */
    public PriceHolder getLastPriceByContractHolder(ContractHolder contractHolder) {
        TSElement bidTsElement = timeSeriesHandler.getInstance()
                .tsGet(TimeSeriesHandler.STREAM_STRING + contractHolder.getStreamRequestId() + ":" + TickType.BID);
        TSElement askTsElement = timeSeriesHandler.getInstance()
                .tsGet(TimeSeriesHandler.STREAM_STRING + contractHolder.getStreamRequestId() + ":" + TickType.ASK);
        return new PriceHolder(bidTsElement.getValue(), askTsElement.getValue());
    }

    /**
     * Checking in Redis if the requested Contract already has a data stream.
     * If so, return with the already stored ContractHolder instead of creating a
     * new one.
     * 
     * @param contract
     * @return
     */
    public ContractHolder getContractHolder(Contract contract) {
        return getContractHolder(contract.conid());
    }

    /**
     * Checking in Redis if the requested Contract already has a data stream.
     * If so, return with the already stored ContractHolder instead of creating a
     * new one.
     * 
     * @param conid
     * @return
     */
    public ContractHolder getContractHolder(int conid) {
        Optional<ContractHolder> contractHolder = contractRepository.findById(conid);
        return contractHolder.orElseGet(() -> {
            TwsResultHolder<ContractHolder> twsResult = tws.requestContractByConid(conid);
            if (!StringUtils.hasLength(twsResult.getError())) {
                contractRepository.save(twsResult.getResult());
                return twsResult.getResult();
            }
            throw new RuntimeException(twsResult.getError());
        });
    }
}
