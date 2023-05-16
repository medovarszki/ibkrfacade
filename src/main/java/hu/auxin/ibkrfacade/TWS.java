package hu.auxin.ibkrfacade;

import com.ib.client.*;
import com.ib.contracts.OptContract;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import redis.clients.jedis.exceptions.JedisDataException;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@Scope("singleton")
public final class TWS implements EWrapper, TwsHandler {

    @Value("${ibkr.tws.host}")
    private String TWS_HOST;

    @Value("${ibkr.tws.port}")
    private int TWS_PORT;

    private final TimeSeriesHandler timeSeriesHandler;
    private final ContractRepository contractRepository;
    private final OrderManagerService orderManagerService;
    private final PositionManagerService positionManagerService;

    private final EReaderSignal readerSignal = new EJavaSignal();
    private final EClientSocket client = new EClientSocket(this, readerSignal);
    private final AtomicInteger autoIncrement = new AtomicInteger();
    private final TwsResultHandler twsResultHandler = new TwsResultHandler();

    TWS(ContractRepository contractRepository, TimeSeriesHandler timeSeriesHandler,
            OrderManagerService orderManagerService, PositionManagerService positionManagerService) {
        this.timeSeriesHandler = timeSeriesHandler;
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void connect() {
        client.eConnect(TWS_HOST, TWS_PORT, 0);

        final EReader reader = new EReader(client, readerSignal);
        reader.start();

        // An additional thread is created in this program design to empty the messaging
        // queue
        new Thread(() -> {
            while (client.isConnected()) {
                readerSignal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }).start();

        client.reqPositions(); // subscribe to positions
        client.reqAutoOpenOrders(true); // subscribe to order changes
        client.reqAllOpenOrders(); // initial request for open orders

        orderManagerService.setClient(client);
    }

    @Override
    public TwsResultHolder<List<Contract>> searchContract(String search) {
        if (StringUtils.hasLength(search)) {
            final int currentId = autoIncrement.getAndIncrement();
            client.reqMatchingSymbols(currentId, search);
            return twsResultHandler.getResult(currentId);
        }
        return new TwsResultHolder("Search parameter cannot be empty");
    }

    @Override
    public TwsResultHolder<ContractHolder> requestContractByConid(int conid) {
        Contract contract = new Contract();
        contract.conid(conid);
        TwsResultHolder<ContractDetails> contractDetails = requestContractDetails(contract);
        ContractHolder contractHolder = new ContractHolder(contractDetails.getResult().contract());
        contractHolder.setDetails(contractDetails.getResult());
        return new TwsResultHolder<>(contractHolder);
    }

    @Override
    public TwsResultHolder<ContractDetails> requestContractDetails(Contract contract) {
        final int currentId = autoIncrement.getAndIncrement();
        client.reqContractDetails(currentId, contract);
        TwsResultHolder<ContractDetails> details = twsResultHandler.getResult(currentId);
        Optional<ContractHolder> contractHolder = contractRepository.findById(details.getResult().conid());
        contractHolder.ifPresent(holder -> {
            holder.setDetails(details.getResult());
            // TODO save from ContractManager
            contractRepository.save(holder);
        });
        return details;
    }

    @Override
    public int subscribeMarketData(Contract contract, boolean tickByTick) {
        final int currentId = autoIncrement.getAndIncrement();
        Optional<ContractHolder> contractHolderOptional = contractRepository.findById(contract.conid());
        ContractHolder contractHolder = contractHolderOptional.orElse(new ContractHolder(contract));
        contractHolder.setStreamRequestId(currentId);
        contractRepository.save(contractHolder);
        try {
            timeSeriesHandler.createStream(currentId, contract);
        } catch (JedisDataException e) {
            log.error(e.getMessage());
        }
        if (tickByTick) {
            client.reqTickByTickData(currentId, contract, "BidAsk", 1, false);
        } else {
            client.reqMktData(currentId, contract, "", false, false, null);
        }
        return currentId;
    }

    @Override
    public Collection<Contract> requestForOptionChain(Contract underlying) {
        final int currentId = autoIncrement.getAndIncrement();
        client.reqSecDefOptParams(currentId, underlying.symbol(), underlying.exchange(),
                underlying.secType().getApiString(), underlying.conid());
        // waitForResult(currentId);
        // return (Collection<Contract>) results.get(currentId);
        return null;
    }

    // -- TWS callbacks

    @Override
    public void connectAck() {
        if (client.isAsyncEConnect()) {
            log.info("Acknowledging connection");
            client.startAPI();
        }
    }

    // ! [tickprice]
    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        TickType tickType = TickType.get(field);
        if (Set.of(TickType.ASK, TickType.BID).contains(tickType)) {
            timeSeriesHandler.addToStream(tickerId, price, tickType);
            log.debug("Tick added to stream {}: {}", tickType, price);
        } else {
            log.debug("Skip tick type {}", tickType);
        }
    }
    // ! [tickprice]

    // ! [ticksize]
    @Override
    public void tickSize(int tickerId, int field, int size) {
        TickType tickType = TickType.get(field);
        // log.info("Tick Size. Ticker Id:" + tickerId + ", Field: " + tickType + ",
        // Size: " + size);
    }
    // ! [ticksize]

    // ! [tickoptioncomputation]
    @Override
    public void tickOptionComputation(int tickerId, int field,
            double impliedVol, double delta, double optPrice,
            double pvDividend, double gamma, double vega, double theta,
            double undPrice) {
        log.info("TickOptionComputation. TickerId: " + tickerId + ", field: " + field + ", ImpliedVolatility: "
                + impliedVol + ", Delta: " + delta
                + ", OptionPrice: " + optPrice + ", pvDividend: " + pvDividend + ", Gamma: " + gamma + ", Vega: " + vega
                + ", Theta: " + theta + ", UnderlyingPrice: " + undPrice);
    }
    // ! [tickoptioncomputation]

    // ! [tickgeneric]
    @Override
    public void tickGeneric(int tickerId, int tickType, double value) {
        log.info("Tick Generic. Ticker Id:" + tickerId + ", Field: " + TickType.getField(tickType) + ", Value: "
                + value);
    }
    // ! [tickgeneric]

    // ! [tickstring]
    @Override
    public void tickString(int tickerId, int tickType, String value) {
        TickType type = TickType.get(tickType);
        // log.info("Tick string. Ticker Id:" + tickerId + ", Type: " + type.name() + ",
        // Value: " + value);
    }

    // ! [tickstring]
    @Override
    public void tickEFP(int tickerId, int tickType, double basisPoints,
            String formattedBasisPoints, double impliedFuture, int holdDays,
            String futureLastTradeDate, double dividendImpact,
            double dividendsToLastTradeDate) {
        log.info("TickEFP. " + tickerId + ", Type: " + tickType + ", BasisPoints: " + basisPoints
                + ", FormattedBasisPoints: " +
                formattedBasisPoints + ", ImpliedFuture: " + impliedFuture + ", HoldDays: " + holdDays
                + ", FutureLastTradeDate: " + futureLastTradeDate +
                ", DividendImpact: " + dividendImpact + ", DividendsToLastTradeDate: " + dividendsToLastTradeDate);
    }

    // ! [orderstatus]
    @Override
    public void orderStatus(int orderId, String status, double filled,
            double remaining, double avgFillPrice, int permId, int parentId,
            double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        orderManagerService.changeOrderStatus(permId, status, filled, remaining, avgFillPrice, lastFillPrice);
    }
    // ! [orderstatus]

    // ! [openorder]
    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        orderManagerService.setOrder(contract, order, orderState);
    }
    // ! [openorder]

    // ! [openorderend]
    @Override
    public void openOrderEnd() {
        log.info("Order list retrieved");
    }
    // ! [openorderend]

    // ! [updateaccountvalue]
    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        log.info("UpdateAccountValue. Key: " + key + ", Value: " + value + ", Currency: " + currency + ", AccountName: "
                + accountName);
    }
    // ! [updateaccountvalue]

    // ! [updateportfolio]
    @Override
    public void updatePortfolio(Contract contract, double position,
            double marketPrice, double marketValue, double averageCost,
            double unrealizedPNL, double realizedPNL, String accountName) {
        log.info("UpdatePortfolio. " + contract.symbol() + ", " + contract.secType() + " @ " + contract.exchange()
                + ": Position: " + position + ", MarketPrice: " + marketPrice + ", MarketValue: " + marketValue
                + ", AverageCost: " + averageCost
                + ", UnrealizedPNL: " + unrealizedPNL + ", RealizedPNL: " + realizedPNL + ", AccountName: "
                + accountName);
    }
    // ! [updateportfolio]

    // ! [updateaccounttime]
    @Override
    public void updateAccountTime(String timeStamp) {
        log.info("UpdateAccountTime. Time: " + timeStamp + "\n");
    }
    // ! [updateaccounttime]

    // ! [accountdownloadend]
    @Override
    public void accountDownloadEnd(String accountName) {
        log.info("Account download finished: " + accountName + "\n");
    }
    // ! [accountdownloadend]

    // ! [nextvalidid]
    @Override
    public void nextValidId(int orderId) {
        this.orderManagerService.setOrderId(orderId);
    }
    // ! [nextvalidid]

    // ! [contractdetails]
    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        twsResultHandler.setResult(reqId, new TwsResultHolder<ContractDetails>(contractDetails));
    }

    // ! [contractdetails]
    @Override
    public void bondContractDetails(int reqId, ContractDetails contractDetails) {
        log.info(EWrapperMsgGenerator.bondContractDetails(reqId, contractDetails));
    }

    // ! [contractdetailsend]
    @Override
    public void contractDetailsEnd(int reqId) {
        log.info("ContractDetailsEnd. " + reqId + "\n");
    }
    // ! [contractdetailsend]

    // ! [execdetails]
    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        log.info("ExecDetails. " + reqId + " - [" + contract.symbol() + "], [" + contract.secType() + "], ["
                + contract.currency() + "], [" + execution.execId() +
                "], [" + execution.orderId() + "], [" + execution.shares() + "]" + ", [" + execution.lastLiquidity()
                + "]");
    }
    // ! [execdetails]

    // ! [execdetailsend]
    @Override
    public void execDetailsEnd(int reqId) {
        log.info("ExecDetailsEnd. " + reqId + "\n");
    }
    // ! [execdetailsend]

    // ! [updatemktdepth]
    @Override
    public void updateMktDepth(int tickerId, int position, int operation,
            int side, double price, int size) {
        log.info("UpdateMarketDepth. " + tickerId + " - Position: " + position + ", Operation: " + operation
                + ", Side: " + side + ", Price: " + price + ", Size: " + size + "");
    }
    // ! [updatemktdepth]

    // ! [updatemktdepthl2]
    @Override
    public void updateMktDepthL2(int tickerId, int position, String marketMaker, int operation, int side, double price,
            int size, boolean isSmartDepth) {
        log.info("UpdateMarketDepthL2. " + tickerId + " - Position: " + position + ", Operation: " + operation
                + ", Side: " + side + ", Price: " + price + ", Size: " + size + ", isSmartDepth: " + isSmartDepth);
    }
    // ! [updatemktdepthl2]

    // ! [updatenewsbulletin]
    @Override
    public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {
        log.info("News Bulletins. " + msgId + " - Type: " + msgType + ", Message: " + message + ", Exchange of Origin: "
                + origExchange + "\n");
    }
    // ! [updatenewsbulletin]

    // ! [managedaccounts]
    @Override
    public void managedAccounts(String accountsList) {
        log.info("Account list: " + accountsList);
    }
    // ! [managedaccounts]

    // ! [receivefa]
    @Override
    public void receiveFA(int faDataType, String xml) {
        log.info("Receiving FA: " + faDataType + " - " + xml);
    }
    // ! [receivefa]

    // ! [historicaldata]
    @Override
    public void historicalData(int reqId, Bar bar) {
        log.info("HistoricalData. " + reqId + " - Date: " + bar.time() + ", Open: " + bar.open() + ", High: "
                + bar.high() + ", Low: " + bar.low() + ", Close: " + bar.close() + ", Volume: " + bar.volume()
                + ", Count: " + bar.count() + ", WAP: " + bar.wap());
    }
    // ! [historicaldata]

    // ! [historicaldataend]
    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        log.info("HistoricalDataEnd. " + reqId + " - Start Date: " + startDateStr + ", End Date: " + endDateStr);
    }
    // ! [historicaldataend]

    // ! [scannerparameters]
    @Override
    public void scannerParameters(String xml) {
        log.info("ScannerParameters. " + xml + "\n");
    }
    // ! [scannerparameters]

    // ! [scannerdata]
    @Override
    public void scannerData(int reqId, int rank,
            ContractDetails contractDetails, String distance, String benchmark,
            String projection, String legsStr) {
        log.info("ScannerData. " + reqId + " - Rank: " + rank + ", Symbol: " + contractDetails.contract().symbol()
                + ", SecType: " + contractDetails.contract().secType() + ", Currency: "
                + contractDetails.contract().currency()
                + ", Distance: " + distance + ", Benchmark: " + benchmark + ", Projection: " + projection
                + ", Legs String: " + legsStr);
    }
    // ! [scannerdata]

    // ! [scannerdataend]
    @Override
    public void scannerDataEnd(int reqId) {
        log.info("ScannerDataEnd. " + reqId);
    }
    // ! [scannerdataend]

    // ! [realtimebar]
    @Override
    public void realtimeBar(int reqId, long time, double open, double high, double low, double close, long volume,
            double wap, int count) {
        log.info("RealTimeBars. " + reqId + " - Time: " + time + ", Open: " + open + ", High: " + high + ", Low: " + low
                + ", Close: " + close + ", Volume: " + volume + ", Count: " + count + ", WAP: " + wap);
    }

    // ! [realtimebar]
    @Override
    public void currentTime(long time) {
        log.info("currentTime");
    }

    // ! [fundamentaldata]
    @Override
    public void fundamentalData(int reqId, String data) {
        log.info("FundamentalData. ReqId: [" + reqId + "] - Data: [" + data + "]");
    }

    // ! [fundamentaldata]
    @Override
    public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {
        log.info("deltaNeutralValidation");
    }

    // ! [ticksnapshotend]
    @Override
    public void tickSnapshotEnd(int reqId) {
        log.info("TickSnapshotEnd: " + reqId);
    }
    // ! [ticksnapshotend]

    // ! [marketdatatype]
    @Override
    public void marketDataType(int reqId, int marketDataType) {
        log.info("MarketDataType. [" + reqId + "], Type: [" + marketDataType + "]\n");
    }
    // ! [marketdatatype]

    // ! [commissionreport]
    @Override
    public void commissionReport(CommissionReport commissionReport) {
        log.info("CommissionReport. [" + commissionReport.execId() + "] - [" + commissionReport.commission() + "] ["
                + commissionReport.currency() + "] RPNL [" + commissionReport.realizedPNL() + "]");
    }
    // ! [commissionreport]

    // ! [position]
    @Override
    public void position(String account, Contract contract, double pos, double avgCost) {
        positionManagerService.addPosition(new PositionHolder(contract, pos, avgCost));
    }
    // ! [position]

    // ! [positionend]
    @Override
    public void positionEnd() {
        log.info("Position list retrieved");
    }
    // ! [positionend]

    // ! [accountsummary]
    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        log.info("Acct Summary. ReqId: " + reqId + ", Acct: " + account + ", Tag: " + tag + ", Value: " + value
                + ", Currency: " + currency);
    }
    // ! [accountsummary]

    // ! [accountsummaryend]
    @Override
    public void accountSummaryEnd(int reqId) {
        log.info("AccountSummaryEnd. Req Id: " + reqId + "\n");
    }

    // ! [accountsummaryend]
    @Override
    public void verifyMessageAPI(String apiData) {
        log.info("verifyMessageAPI");
    }

    @Override
    public void verifyCompleted(boolean isSuccessful, String errorText) {
        log.info("verifyCompleted");
    }

    @Override
    public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {
        log.info("verifyAndAuthMessageAPI");
    }

    @Override
    public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {
        log.info("verifyAndAuthCompleted");
    }

    // ! [displaygrouplist]
    @Override
    public void displayGroupList(int reqId, String groups) {
        log.info("Display Group List. ReqId: " + reqId + ", Groups: " + groups + "\n");
    }
    // ! [displaygrouplist]

    // ! [displaygroupupdated]
    @Override
    public void displayGroupUpdated(int reqId, String contractInfo) {
        log.info("Display Group Updated. ReqId: " + reqId + ", Contract info: " + contractInfo + "\n");
    }

    // ! [positionmulti]
    @Override
    public void positionMulti(int reqId, String account, String modelCode, Contract contract, double pos,
            double avgCost) {
        log.info("Position Multi. Request: " + reqId + ", Account: " + account + ", ModelCode: " + modelCode
                + ", Symbol: " + contract.symbol() + ", SecType: " + contract.secType() + ", Currency: "
                + contract.currency() + ", Position: " + pos + ", Avg cost: " + avgCost + "\n");
    }
    // ! [positionmulti]

    // ! [positionmultiend]
    @Override
    public void positionMultiEnd(int reqId) {
        log.info("Position Multi End. Request: " + reqId + "\n");
    }
    // ! [positionmultiend]

    // ! [accountupdatemulti]
    @Override
    public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value,
            String currency) {
        log.info("Account Update Multi. Request: " + reqId + ", Account: " + account + ", ModelCode: " + modelCode
                + ", Key: " + key + ", Value: " + value + ", Currency: " + currency + "\n");
    }
    // ! [accountupdatemulti]

    // ! [accountupdatemultiend]
    @Override
    public void accountUpdateMultiEnd(int reqId) {
        log.info("Account Update Multi End. Request: " + reqId + "\n");
    }
    // ! [accountupdatemultiend]

    // ! [securityDefinitionOptionParameter]
    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId,
            String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        log.info("securityDefinitionOptionalParameter");
        ContractHolder underlyingContractHolder = contractRepository.findById(underlyingConId).orElseGet(() -> {
            TwsResultHolder<ContractHolder> holder = requestContractByConid(underlyingConId);
            return holder.getResult();
        });
        for (Types.Right right : new Types.Right[] { Types.Right.Call, Types.Right.Put }) {
            for (String expiration : expirations) {
                for (Double strike : strikes) {
                    Contract option = new OptContract(null, exchange, expiration, strike, right.getApiString());
                    ContractHolder optionHolder = new ContractHolder(option);
                    contractRepository.save(optionHolder);
                    underlyingContractHolder.getOptionChain().add(optionHolder);
                }
            }
        }
        underlyingContractHolder.setOptionChainRequestId(reqId);
        contractRepository.save(underlyingContractHolder);
    }
    // ! [securityDefinitionOptionParameter]

    // ! [securityDefinitionOptionParameterEnd]
    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        ContractHolder underlying = contractRepository.findContractHolderByOptionChainRequestId(reqId);
        if (underlying != null && !CollectionUtils.isEmpty(underlying.getOptionChain())) {
            twsResultHandler.setResult(reqId, new TwsResultHolder<>(underlying.getOptionChain()));
        }
        log.debug("Option chain retrieved");
    }
    // ! [securityDefinitionOptionParameterEnd]

    // ! [softDollarTiers]
    @Override
    public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {
        for (SoftDollarTier tier : tiers) {
            log.info("tier: " + tier.toString() + ", ");
        }
    }
    // ! [softDollarTiers]

    // ! [familyCodes]
    @Override
    public void familyCodes(FamilyCode[] familyCodes) {
        for (FamilyCode fc : familyCodes) {
            log.info("Family Code. AccountID: " + fc.accountID() + ", FamilyCode: " + fc.familyCodeStr());
        }
    }
    // ! [familyCodes]

    // ! [symbolSamples]
    @Override
    public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
        List<Contract> resultList = new ArrayList<>();
        for (ContractDescription cd : contractDescriptions) {
            resultList.add(cd.contract());
        }
        twsResultHandler.setResult(reqId, new TwsResultHolder(resultList));
    }
    // ! [symbolSamples]

    // ! [mktDepthExchanges]
    @Override
    public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {
        for (DepthMktDataDescription depthMktDataDescription : depthMktDataDescriptions) {
            log.info("Depth Mkt Data Description. Exchange: " + depthMktDataDescription.exchange() +
                    ", ListingExch: " + depthMktDataDescription.listingExch() +
                    ", SecType: " + depthMktDataDescription.secType() +
                    ", ServiceDataType: " + depthMktDataDescription.serviceDataType() +
                    ", AggGroup: " + depthMktDataDescription.aggGroup());
        }
    }
    // ! [mktDepthExchanges]

    // ! [tickNews]
    @Override
    public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline,
            String extraData) {
        log.info("Tick News. TickerId: " + tickerId + ", TimeStamp: " + timeStamp + ", ProviderCode: " + providerCode
                + ", ArticleId: " + articleId + ", Headline: " + headline + ", ExtraData: " + extraData + "\n");
    }
    // ! [tickNews]

    // ! [smartcomponents]
    @Override
    public void smartComponents(int reqId, Map<Integer, Map.Entry<String, Character>> theMap) {
        log.info("smart components req id:" + reqId);

        for (Map.Entry<Integer, Map.Entry<String, Character>> item : theMap.entrySet()) {
            log.info("bit number: " + item.getKey() +
                    ", exchange: " + item.getValue().getKey() + ", exchange letter: " + item.getValue().getValue());
        }
    }
    // ! [smartcomponents]

    // ! [tickReqParams]
    @Override
    public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
        log.info("Tick req params. Ticker Id:" + tickerId + ", Min tick: " + minTick + ", bbo exchange: " + bboExchange
                + ", Snapshot permissions: " + snapshotPermissions);
    }
    // ! [tickReqParams]

    // ! [newsProviders]
    @Override
    public void newsProviders(NewsProvider[] newsProviders) {
        for (NewsProvider np : newsProviders) {
            log.info("News Provider. ProviderCode: " + np.providerCode() + ", ProviderName: " + np.providerName()
                    + "\n");
        }
    }
    // ! [newsProviders]

    // ! [newsArticle]
    @Override
    public void newsArticle(int requestId, int articleType, String articleText) {
        log.info("News Article. Request Id: " + requestId + ", ArticleType: " + articleType +
                ", ArticleText: " + articleText);
    }
    // ! [newsArticle]

    // ! [historicalNews]
    @Override
    public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
        log.info("Historical News. RequestId: " + requestId + ", Time: " + time + ", ProviderCode: " + providerCode
                + ", ArticleId: " + articleId + ", Headline: " + headline + "\n");
    }
    // ! [historicalNews]

    // ! [historicalNewsEnd]
    @Override
    public void historicalNewsEnd(int requestId, boolean hasMore) {
        log.info("Historical News End. RequestId: " + requestId + ", HasMore: " + hasMore + "\n");
    }
    // ! [historicalNewsEnd]

    // ! [headTimestamp]
    @Override
    public void headTimestamp(int reqId, String headTimestamp) {
        log.info("Head timestamp. Req Id: " + reqId + ", headTimestamp: " + headTimestamp);
    }
    // ! [headTimestamp]

    // ! [histogramData]
    @Override
    public void histogramData(int reqId, List<HistogramEntry> items) {
        log.info(EWrapperMsgGenerator.histogramData(reqId, items));
    }
    // ! [histogramData]

    // ! [historicalDataUpdate]
    @Override
    public void historicalDataUpdate(int reqId, Bar bar) {
        log.info("HistoricalDataUpdate. " + reqId + " - Date: " + bar.time() + ", Open: " + bar.open() + ", High: "
                + bar.high() + ", Low: " + bar.low() + ", Close: " + bar.close() + ", Volume: " + bar.volume()
                + ", Count: " + bar.count() + ", WAP: " + bar.wap());
    }
    // ! [historicalDataUpdate]

    // ! [rerouteMktDataReq]
    @Override
    public void rerouteMktDataReq(int reqId, int conId, String exchange) {
        log.info(EWrapperMsgGenerator.rerouteMktDataReq(reqId, conId, exchange));
    }
    // ! [rerouteMktDataReq]

    // ! [rerouteMktDepthReq]
    @Override
    public void rerouteMktDepthReq(int reqId, int conId, String exchange) {
        log.info(EWrapperMsgGenerator.rerouteMktDepthReq(reqId, conId, exchange));
    }
    // ! [rerouteMktDepthReq]

    // ! [marketRule]
    @Override
    public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {
        DecimalFormat df = new DecimalFormat("#.#");
        df.setMaximumFractionDigits(340);
        log.info("Market Rule Id: " + marketRuleId);
        for (PriceIncrement pi : priceIncrements) {
            log.info("Price Increment. Low Edge: " + df.format(pi.lowEdge()) + ", Increment: "
                    + df.format(pi.increment()));
        }
    }
    // ! [marketRule]

    // ! [pnl]
    @Override
    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
        log.info(EWrapperMsgGenerator.pnl(reqId, dailyPnL, unrealizedPnL, realizedPnL));
    }
    // ! [pnl]

    // ! [pnlsingle]
    @Override
    public void pnlSingle(int reqId, int pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
        log.info(EWrapperMsgGenerator.pnlSingle(reqId, pos, dailyPnL, unrealizedPnL, realizedPnL, value));
    }
    // ! [pnlsingle]

    // ! [historicalticks]
    @Override
    public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done) {
        for (HistoricalTick tick : ticks) {
            log.info(EWrapperMsgGenerator.historicalTick(reqId, tick.time(), tick.price(), tick.size()));
        }
    }
    // ! [historicalticks]

    // ! [historicalticksbidask]
    @Override
    public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {
        for (HistoricalTickBidAsk tick : ticks) {
            log.info(EWrapperMsgGenerator.historicalTickBidAsk(reqId, tick.time(), tick.tickAttribBidAsk(),
                    tick.priceBid(), tick.priceAsk(), tick.sizeBid(),
                    tick.sizeAsk()));
        }
    }
    // ! [historicalticksbidask]

    @Override
    // ! [historicaltickslast]
    public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {
        for (HistoricalTickLast tick : ticks) {
            log.info(EWrapperMsgGenerator.historicalTickLast(reqId, tick.time(), tick.tickAttribLast(), tick.price(),
                    tick.size(), tick.exchange(),
                    tick.specialConditions()));
        }
    }
    // ! [historicaltickslast]

    // ! [tickbytickalllast]
    @Override
    public void tickByTickAllLast(int reqId, int tickType, long time, double price, int size,
            TickAttribLast tickAttribLast,
            String exchange, String specialConditions) {
        log.info(EWrapperMsgGenerator.tickByTickAllLast(reqId, tickType, time, price, size, tickAttribLast, exchange,
                specialConditions));
    }
    // ! [tickbytickalllast]

    // ! [tickbytickbidask]
    @Override
    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, int bidSize, int askSize,
            TickAttribBidAsk tickAttribBidAsk) {
        timeSeriesHandler.addToStream(reqId, bidPrice, TickType.BID);
        timeSeriesHandler.addToStream(reqId, askPrice, TickType.ASK);
    }
    // ! [tickbytickbidask]

    // ! [tickbytickmidpoint]
    @Override
    public void tickByTickMidPoint(int reqId, long time, double midPoint) {
        log.info(EWrapperMsgGenerator.tickByTickMidPoint(reqId, time, midPoint));
    }
    // ! [tickbytickmidpoint]

    // ! [orderbound]
    @Override
    public void orderBound(long orderId, int apiClientId, int apiOrderId) {
        log.info(EWrapperMsgGenerator.orderBound(orderId, apiClientId, apiOrderId));
    }
    // ! [orderbound]

    // ! [completedorder]
    @Override
    public void completedOrder(Contract contract, Order order, OrderState orderState) {
        log.info(EWrapperMsgGenerator.completedOrder(contract, order, orderState));
    }
    // ! [completedorder]

    // ! [completedordersend]
    @Override
    public void completedOrdersEnd() {
        log.info(EWrapperMsgGenerator.completedOrdersEnd());
    }
    // ! [completedordersend]

    // ! [displaygroupupdated]
    @Override
    public void error(Exception e) {
        log.error(e.getMessage());
    }

    @Override
    public void error(String str) {
        log.error(str);
    }

    // ! [error]
    @Override
    public void error(int id, int errorCode, String errorMsg) {
        log.error("Error id: {}; Code: {}: {}", id, errorCode, errorMsg);
        twsResultHandler.setResult(id, new TwsResultHolder("Error code: " + errorCode + "; " + errorMsg));
    }

    // ! [error]
    @Override
    public void connectionClosed() {
        log.info("Connection closed");
    }

}
