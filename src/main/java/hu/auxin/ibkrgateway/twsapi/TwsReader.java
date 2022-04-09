package hu.auxin.ibkrgateway.twsapi;

import com.ib.client.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class TwsReader implements EWrapper {

    private static final Logger LOG = LogManager.getLogger(TwsReader.class);

    private Map<Integer, Object> resultMap;

    //! [socket_declare]
    private EReaderSignal readerSignal;
    private EClientSocket clientSocket;
    protected int currentOrderId = -1;
    //! [socket_declare]

    //! [socket_init]
    public TwsReader(Map<Integer, Object> resultMap) {
        this.resultMap = resultMap;
        readerSignal = new EJavaSignal();
        clientSocket = new EClientSocket(this, readerSignal);
    }

    //! [socket_init]
    public EClientSocket getClient() {
        return clientSocket;
    }

    public EReaderSignal getSignal() {
        return readerSignal;
    }

    public int getCurrentOrderId() {
        return currentOrderId;
    }

    //! [tickprice]
    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
//        Contract c = (Contract) redisTemplate.opsForValue().get(tickerId);
        System.out.println("Tick Price. Ticker Id:" + tickerId + ", Field: " + field + ", Price: " + price + ", CanAutoExecute: " + attribs.canAutoExecute() + ", pastLimit: " + attribs.pastLimit() + ", pre-open: " + attribs.preOpen());
    }
    //! [tickprice]

    //! [ticksize]
    @Override
    public void tickSize(int tickerId, int field, int size) {
        TickType tickType = TickType.get(field);
        System.out.println("Tick Size. Ticker Id:" + tickerId + ", Field: " + field + ", Size: " + size);
    }
    //! [ticksize]

    //! [tickoptioncomputation]
    @Override
    public void tickOptionComputation(int tickerId, int field,
                                      double impliedVol, double delta, double optPrice,
                                      double pvDividend, double gamma, double vega, double theta,
                                      double undPrice) {
        System.out.println("TickOptionComputation. TickerId: " + tickerId + ", field: " + field + ", ImpliedVolatility: " + impliedVol + ", Delta: " + delta
                + ", OptionPrice: " + optPrice + ", pvDividend: " + pvDividend + ", Gamma: " + gamma + ", Vega: " + vega + ", Theta: " + theta + ", UnderlyingPrice: " + undPrice);
    }
    //! [tickoptioncomputation]

    //! [tickgeneric]
    @Override
    public void tickGeneric(int tickerId, int tickType, double value) {
        System.out.println("Tick Generic. Ticker Id:" + tickerId + ", Field: " + TickType.getField(tickType) + ", Value: " + value);
    }
    //! [tickgeneric]

    //! [tickstring]
    @Override
    public void tickString(int tickerId, int tickType, String value) {
        TickType type = TickType.get(tickType);
        System.out.println("Tick string. Ticker Id:" + tickerId + ", Type: " + type.name() + ", Value: " + value);
    }

    //! [tickstring]
    @Override
    public void tickEFP(int tickerId, int tickType, double basisPoints,
                        String formattedBasisPoints, double impliedFuture, int holdDays,
                        String futureLastTradeDate, double dividendImpact,
                        double dividendsToLastTradeDate) {
        System.out.println("TickEFP. " + tickerId + ", Type: " + tickType + ", BasisPoints: " + basisPoints + ", FormattedBasisPoints: " +
                formattedBasisPoints + ", ImpliedFuture: " + impliedFuture + ", HoldDays: " + holdDays + ", FutureLastTradeDate: " + futureLastTradeDate +
                ", DividendImpact: " + dividendImpact + ", DividendsToLastTradeDate: " + dividendsToLastTradeDate);
    }

    //! [orderstatus]
    @Override
    public void orderStatus(int orderId, String status, double filled,
                            double remaining, double avgFillPrice, int permId, int parentId,
                            double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        System.out.println("OrderStatus. Id: " + orderId + ", Status: " + status + ", Filled" + filled + ", Remaining: " + remaining
                + ", AvgFillPrice: " + avgFillPrice + ", PermId: " + permId + ", ParentId: " + parentId + ", LastFillPrice: " + lastFillPrice +
                ", ClientId: " + clientId + ", WhyHeld: " + whyHeld + ", MktCapPrice: " + mktCapPrice);
    }
    //! [orderstatus]

    //! [openorder]
    @Override
    public void openOrder(int orderId, Contract contract, Order order,
                          OrderState orderState) {
        System.out.println(EWrapperMsgGenerator.openOrder(orderId, contract, order, orderState));
    }
    //! [openorder]

    //! [openorderend]
    @Override
    public void openOrderEnd() {
        System.out.println("OpenOrderEnd");
    }
    //! [openorderend]

    //! [updateaccountvalue]
    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        System.out.println("UpdateAccountValue. Key: " + key + ", Value: " + value + ", Currency: " + currency + ", AccountName: " + accountName);
    }
    //! [updateaccountvalue]

    //! [updateportfolio]
    @Override
    public void updatePortfolio(Contract contract, double position,
                                double marketPrice, double marketValue, double averageCost,
                                double unrealizedPNL, double realizedPNL, String accountName) {
        System.out.println("UpdatePortfolio. " + contract.symbol() + ", " + contract.secType() + " @ " + contract.exchange()
                + ": Position: " + position + ", MarketPrice: " + marketPrice + ", MarketValue: " + marketValue + ", AverageCost: " + averageCost
                + ", UnrealizedPNL: " + unrealizedPNL + ", RealizedPNL: " + realizedPNL + ", AccountName: " + accountName);
    }
    //! [updateportfolio]

    //! [updateaccounttime]
    @Override
    public void updateAccountTime(String timeStamp) {
        System.out.println("UpdateAccountTime. Time: " + timeStamp + "\n");
    }
    //! [updateaccounttime]

    //! [accountdownloadend]
    @Override
    public void accountDownloadEnd(String accountName) {
        System.out.println("Account download finished: " + accountName + "\n");
    }
    //! [accountdownloadend]

    //! [nextvalidid]
    @Override
    public void nextValidId(int orderId) {
        System.out.println("Next Valid Id: [" + orderId + "]");
        currentOrderId = orderId;
    }
    //! [nextvalidid]

    //! [contractdetails]
    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        System.out.println(EWrapperMsgGenerator.contractDetails(reqId, contractDetails));
    }

    //! [contractdetails]
    @Override
    public void bondContractDetails(int reqId, ContractDetails contractDetails) {
        System.out.println(EWrapperMsgGenerator.bondContractDetails(reqId, contractDetails));
    }

    //! [contractdetailsend]
    @Override
    public void contractDetailsEnd(int reqId) {
        System.out.println("ContractDetailsEnd. " + reqId + "\n");
    }
    //! [contractdetailsend]

    //! [execdetails]
    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        System.out.println("ExecDetails. " + reqId + " - [" + contract.symbol() + "], [" + contract.secType() + "], [" + contract.currency() + "], [" + execution.execId() +
                "], [" + execution.orderId() + "], [" + execution.shares() + "]" + ", [" + execution.lastLiquidity() + "]");
    }
    //! [execdetails]

    //! [execdetailsend]
    @Override
    public void execDetailsEnd(int reqId) {
        System.out.println("ExecDetailsEnd. " + reqId + "\n");
    }
    //! [execdetailsend]

    //! [updatemktdepth]
    @Override
    public void updateMktDepth(int tickerId, int position, int operation,
                               int side, double price, int size) {
        System.out.println("UpdateMarketDepth. " + tickerId + " - Position: " + position + ", Operation: " + operation + ", Side: " + side + ", Price: " + price + ", Size: " + size + "");
    }
    //! [updatemktdepth]

    //! [updatemktdepthl2]
    @Override
    public void updateMktDepthL2(int tickerId, int position, String marketMaker, int operation, int side, double price, int size, boolean isSmartDepth) {
        System.out.println("UpdateMarketDepthL2. " + tickerId + " - Position: " + position + ", Operation: " + operation + ", Side: " + side + ", Price: " + price + ", Size: " + size + ", isSmartDepth: " + isSmartDepth);
    }
    //! [updatemktdepthl2]

    //! [updatenewsbulletin]
    @Override
    public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {
        System.out.println("News Bulletins. " + msgId + " - Type: " + msgType + ", Message: " + message + ", Exchange of Origin: " + origExchange + "\n");
    }
    //! [updatenewsbulletin]

    //! [managedaccounts]
    @Override
    public void managedAccounts(String accountsList) {
        System.out.println("Account list: " + accountsList);
    }
    //! [managedaccounts]

    //! [receivefa]
    @Override
    public void receiveFA(int faDataType, String xml) {
        System.out.println("Receiving FA: " + faDataType + " - " + xml);
    }
    //! [receivefa]

    //! [historicaldata]
    @Override
    public void historicalData(int reqId, Bar bar) {
        System.out.println("HistoricalData. " + reqId + " - Date: " + bar.time() + ", Open: " + bar.open() + ", High: " + bar.high() + ", Low: " + bar.low() + ", Close: " + bar.close() + ", Volume: " + bar.volume() + ", Count: " + bar.count() + ", WAP: " + bar.wap());
    }
    //! [historicaldata]

    //! [historicaldataend]
    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        System.out.println("HistoricalDataEnd. " + reqId + " - Start Date: " + startDateStr + ", End Date: " + endDateStr);
    }
    //! [historicaldataend]


    //! [scannerparameters]
    @Override
    public void scannerParameters(String xml) {
        System.out.println("ScannerParameters. " + xml + "\n");
    }
    //! [scannerparameters]

    //! [scannerdata]
    @Override
    public void scannerData(int reqId, int rank,
                            ContractDetails contractDetails, String distance, String benchmark,
                            String projection, String legsStr) {
        System.out.println("ScannerData. " + reqId + " - Rank: " + rank + ", Symbol: " + contractDetails.contract().symbol() + ", SecType: " + contractDetails.contract().secType() + ", Currency: " + contractDetails.contract().currency()
                + ", Distance: " + distance + ", Benchmark: " + benchmark + ", Projection: " + projection + ", Legs String: " + legsStr);
    }
    //! [scannerdata]

    //! [scannerdataend]
    @Override
    public void scannerDataEnd(int reqId) {
        System.out.println("ScannerDataEnd. " + reqId);
    }
    //! [scannerdataend]

    //! [realtimebar]
    @Override
    public void realtimeBar(int reqId, long time, double open, double high, double low, double close, long volume, double wap, int count) {
        System.out.println("RealTimeBars. " + reqId + " - Time: " + time + ", Open: " + open + ", High: " + high + ", Low: " + low + ", Close: " + close + ", Volume: " + volume + ", Count: " + count + ", WAP: " + wap);
    }

    //! [realtimebar]
    @Override
    public void currentTime(long time) {
        System.out.println("currentTime");
    }

    //! [fundamentaldata]
    @Override
    public void fundamentalData(int reqId, String data) {
        System.out.println("FundamentalData. ReqId: [" + reqId + "] - Data: [" + data + "]");
    }

    //! [fundamentaldata]
    @Override
    public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {
        System.out.println("deltaNeutralValidation");
    }

    //! [ticksnapshotend]
    @Override
    public void tickSnapshotEnd(int reqId) {
        System.out.println("TickSnapshotEnd: " + reqId);
    }
    //! [ticksnapshotend]

    //! [marketdatatype]
    @Override
    public void marketDataType(int reqId, int marketDataType) {
        System.out.println("MarketDataType. [" + reqId + "], Type: [" + marketDataType + "]\n");
    }
    //! [marketdatatype]

    //! [commissionreport]
    @Override
    public void commissionReport(CommissionReport commissionReport) {
        System.out.println("CommissionReport. [" + commissionReport.execId() + "] - [" + commissionReport.commission() + "] [" + commissionReport.currency() + "] RPNL [" + commissionReport.realizedPNL() + "]");
    }
    //! [commissionreport]

    //! [position]
    @Override
    public void position(String account, Contract contract, double pos, double avgCost) {
        System.out.println("Position. " + account + " - Symbol: " + contract.symbol() + ", SecType: " + contract.secType() + ", Currency: " + contract.currency() + ", Position: " + pos + ", Avg cost: " + avgCost);
    }
    //! [position]

    //! [positionend]
    @Override
    public void positionEnd() {
        System.out.println("PositionEnd \n");
    }
    //! [positionend]

    //! [accountsummary]
    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        System.out.println("Acct Summary. ReqId: " + reqId + ", Acct: " + account + ", Tag: " + tag + ", Value: " + value + ", Currency: " + currency);
    }
    //! [accountsummary]

    //! [accountsummaryend]
    @Override
    public void accountSummaryEnd(int reqId) {
        System.out.println("AccountSummaryEnd. Req Id: " + reqId + "\n");
    }

    //! [accountsummaryend]
    @Override
    public void verifyMessageAPI(String apiData) {
        System.out.println("verifyMessageAPI");
    }

    @Override
    public void verifyCompleted(boolean isSuccessful, String errorText) {
        System.out.println("verifyCompleted");
    }

    @Override
    public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {
        System.out.println("verifyAndAuthMessageAPI");
    }

    @Override
    public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {
        System.out.println("verifyAndAuthCompleted");
    }

    //! [displaygrouplist]
    @Override
    public void displayGroupList(int reqId, String groups) {
        System.out.println("Display Group List. ReqId: " + reqId + ", Groups: " + groups + "\n");
    }
    //! [displaygrouplist]

    //! [displaygroupupdated]
    @Override
    public void displayGroupUpdated(int reqId, String contractInfo) {
        System.out.println("Display Group Updated. ReqId: " + reqId + ", Contract info: " + contractInfo + "\n");
    }

    //! [connectack]
    @Override
    public void connectAck() {
        if (clientSocket.isAsyncEConnect()) {
            System.out.println("Acknowledging connection");
            clientSocket.startAPI();
        }
    }
    //! [connectack]

    //! [positionmulti]
    @Override
    public void positionMulti(int reqId, String account, String modelCode, Contract contract, double pos, double avgCost) {
        System.out.println("Position Multi. Request: " + reqId + ", Account: " + account + ", ModelCode: " + modelCode + ", Symbol: " + contract.symbol() + ", SecType: " + contract.secType() + ", Currency: " + contract.currency() + ", Position: " + pos + ", Avg cost: " + avgCost + "\n");
    }
    //! [positionmulti]

    //! [positionmultiend]
    @Override
    public void positionMultiEnd(int reqId) {
        System.out.println("Position Multi End. Request: " + reqId + "\n");
    }
    //! [positionmultiend]

    //! [accountupdatemulti]
    @Override
    public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency) {
        System.out.println("Account Update Multi. Request: " + reqId + ", Account: " + account + ", ModelCode: " + modelCode + ", Key: " + key + ", Value: " + value + ", Currency: " + currency + "\n");
    }
    //! [accountupdatemulti]

    //! [accountupdatemultiend]
    @Override
    public void accountUpdateMultiEnd(int reqId) {
        System.out.println("Account Update Multi End. Request: " + reqId + "\n");
    }
    //! [accountupdatemultiend]

    //! [securityDefinitionOptionParameter]
    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
    }
    //! [securityDefinitionOptionParameter]

    //! [securityDefinitionOptionParameterEnd]
    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        //System.out.println("Security Definition Optional Parameter End. Request: " + reqId);
    }
    //! [securityDefinitionOptionParameterEnd]

    //! [softDollarTiers]
    @Override
    public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {
        for (SoftDollarTier tier : tiers) {
            System.out.print("tier: " + tier.toString() + ", ");
        }

        System.out.println();
    }
    //! [softDollarTiers]

    //! [familyCodes]
    @Override
    public void familyCodes(FamilyCode[] familyCodes) {
        for (FamilyCode fc : familyCodes) {
            System.out.print("Family Code. AccountID: " + fc.accountID() + ", FamilyCode: " + fc.familyCodeStr());
        }

        System.out.println();
    }
    //! [familyCodes]

    //! [symbolSamples]
    @Override
    public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
        List<Contract> resultList = new ArrayList<>();
        for (ContractDescription cd : contractDescriptions) {
            resultList.add(cd.contract());
        }
        resultMap.put(reqId, resultList);
    }
    //! [symbolSamples]

    //! [mktDepthExchanges]
    @Override
    public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {
        for (DepthMktDataDescription depthMktDataDescription : depthMktDataDescriptions) {
            System.out.println("Depth Mkt Data Description. Exchange: " + depthMktDataDescription.exchange() +
                    ", ListingExch: " + depthMktDataDescription.listingExch() +
                    ", SecType: " + depthMktDataDescription.secType() +
                    ", ServiceDataType: " + depthMktDataDescription.serviceDataType() +
                    ", AggGroup: " + depthMktDataDescription.aggGroup()
            );
        }
    }
    //! [mktDepthExchanges]

    //! [tickNews]
    @Override
    public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {
        System.out.println("Tick News. TickerId: " + tickerId + ", TimeStamp: " + timeStamp + ", ProviderCode: " + providerCode + ", ArticleId: " + articleId + ", Headline: " + headline + ", ExtraData: " + extraData + "\n");
    }
    //! [tickNews]

    //! [smartcomponents]
    @Override
    public void smartComponents(int reqId, Map<Integer, Entry<String, Character>> theMap) {
        System.out.println("smart components req id:" + reqId);

        for (Entry<Integer, Entry<String, Character>> item : theMap.entrySet()) {
            System.out.println("bit number: " + item.getKey() +
                    ", exchange: " + item.getValue().getKey() + ", exchange letter: " + item.getValue().getValue());
        }
    }
    //! [smartcomponents]

    //! [tickReqParams]
    @Override
    public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
        System.out.println("Tick req params. Ticker Id:" + tickerId + ", Min tick: " + minTick + ", bbo exchange: " + bboExchange + ", Snapshot permissions: " + snapshotPermissions);
    }
    //! [tickReqParams]

    //! [newsProviders]
    @Override
    public void newsProviders(NewsProvider[] newsProviders) {
        for (NewsProvider np : newsProviders) {
            System.out.print("News Provider. ProviderCode: " + np.providerCode() + ", ProviderName: " + np.providerName() + "\n");
        }

        System.out.println();
    }
    //! [newsProviders]

    //! [newsArticle]
    @Override
    public void newsArticle(int requestId, int articleType, String articleText) {
        System.out.println("News Article. Request Id: " + requestId + ", ArticleType: " + articleType +
                ", ArticleText: " + articleText);
    }
    //! [newsArticle]

    //! [historicalNews]
    @Override
    public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
        System.out.println("Historical News. RequestId: " + requestId + ", Time: " + time + ", ProviderCode: " + providerCode + ", ArticleId: " + articleId + ", Headline: " + headline + "\n");
    }
    //! [historicalNews]

    //! [historicalNewsEnd]
    @Override
    public void historicalNewsEnd(int requestId, boolean hasMore) {
        System.out.println("Historical News End. RequestId: " + requestId + ", HasMore: " + hasMore + "\n");
    }
    //! [historicalNewsEnd]

    //! [headTimestamp]
    @Override
    public void headTimestamp(int reqId, String headTimestamp) {
        System.out.println("Head timestamp. Req Id: " + reqId + ", headTimestamp: " + headTimestamp);
    }
    //! [headTimestamp]

    //! [histogramData]
    @Override
    public void histogramData(int reqId, List<HistogramEntry> items) {
        System.out.println(EWrapperMsgGenerator.histogramData(reqId, items));
    }
    //! [histogramData]

    //! [historicalDataUpdate]
    @Override
    public void historicalDataUpdate(int reqId, Bar bar) {
        System.out.println("HistoricalDataUpdate. " + reqId + " - Date: " + bar.time() + ", Open: " + bar.open() + ", High: " + bar.high() + ", Low: " + bar.low() + ", Close: " + bar.close() + ", Volume: " + bar.volume() + ", Count: " + bar.count() + ", WAP: " + bar.wap());
    }
    //! [historicalDataUpdate]

    //! [rerouteMktDataReq]
    @Override
    public void rerouteMktDataReq(int reqId, int conId, String exchange) {
        System.out.println(EWrapperMsgGenerator.rerouteMktDataReq(reqId, conId, exchange));
    }
    //! [rerouteMktDataReq]

    //! [rerouteMktDepthReq]
    @Override
    public void rerouteMktDepthReq(int reqId, int conId, String exchange) {
        System.out.println(EWrapperMsgGenerator.rerouteMktDepthReq(reqId, conId, exchange));
    }
    //! [rerouteMktDepthReq]

    //! [marketRule]
    @Override
    public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {
        DecimalFormat df = new DecimalFormat("#.#");
        df.setMaximumFractionDigits(340);
        System.out.println("Market Rule Id: " + marketRuleId);
        for (PriceIncrement pi : priceIncrements) {
            System.out.println("Price Increment. Low Edge: " + df.format(pi.lowEdge()) + ", Increment: " + df.format(pi.increment()));
        }
    }
    //! [marketRule]

    //! [pnl]
    @Override
    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
        System.out.println(EWrapperMsgGenerator.pnl(reqId, dailyPnL, unrealizedPnL, realizedPnL));
    }
    //! [pnl]

    //! [pnlsingle]
    @Override
    public void pnlSingle(int reqId, int pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
        System.out.println(EWrapperMsgGenerator.pnlSingle(reqId, pos, dailyPnL, unrealizedPnL, realizedPnL, value));
    }
    //! [pnlsingle]

    //! [historicalticks]
    @Override
    public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done) {
        for (HistoricalTick tick : ticks) {
            System.out.println(EWrapperMsgGenerator.historicalTick(reqId, tick.time(), tick.price(), tick.size()));
        }
    }
    //! [historicalticks]

    //! [historicalticksbidask]
    @Override
    public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {
        for (HistoricalTickBidAsk tick : ticks) {
            System.out.println(EWrapperMsgGenerator.historicalTickBidAsk(reqId, tick.time(), tick.tickAttribBidAsk(), tick.priceBid(), tick.priceAsk(), tick.sizeBid(),
                    tick.sizeAsk()));
        }
    }
    //! [historicalticksbidask]

    @Override
    //! [historicaltickslast]
    public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {
        for (HistoricalTickLast tick : ticks) {
            System.out.println(EWrapperMsgGenerator.historicalTickLast(reqId, tick.time(), tick.tickAttribLast(), tick.price(), tick.size(), tick.exchange(),
                    tick.specialConditions()));
        }
    }
    //! [historicaltickslast]

    //! [tickbytickalllast]
    @Override
    public void tickByTickAllLast(int reqId, int tickType, long time, double price, int size, TickAttribLast tickAttribLast,
                                  String exchange, String specialConditions) {
        System.out.println(EWrapperMsgGenerator.tickByTickAllLast(reqId, tickType, time, price, size, tickAttribLast, exchange, specialConditions));
    }
    //! [tickbytickalllast]

    //! [tickbytickbidask]
    @Override
    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, int bidSize, int askSize,
                                 TickAttribBidAsk tickAttribBidAsk) {
        System.out.println(EWrapperMsgGenerator.tickByTickBidAsk(reqId, time, bidPrice, askPrice, bidSize, askSize, tickAttribBidAsk));
    }
    //! [tickbytickbidask]

    //! [tickbytickmidpoint]
    @Override
    public void tickByTickMidPoint(int reqId, long time, double midPoint) {
        System.out.println(EWrapperMsgGenerator.tickByTickMidPoint(reqId, time, midPoint));
    }
    //! [tickbytickmidpoint]

    //! [orderbound]
    @Override
    public void orderBound(long orderId, int apiClientId, int apiOrderId) {
        System.out.println(EWrapperMsgGenerator.orderBound(orderId, apiClientId, apiOrderId));
    }
    //! [orderbound]

    //! [completedorder]
    @Override
    public void completedOrder(Contract contract, Order order, OrderState orderState) {
        System.out.println(EWrapperMsgGenerator.completedOrder(contract, order, orderState));
    }
    //! [completedorder]

    //! [completedordersend]
    @Override
    public void completedOrdersEnd() {
        System.out.println(EWrapperMsgGenerator.completedOrdersEnd());
    }
    //! [completedordersend]


    //! [displaygroupupdated]
    @Override
    public void error(Exception e) {
        LOG.error(e);
    }

    @Override
    public void error(String str) {
        LOG.error(str);
    }

    //! [error]
    @Override
    public void error(int id, int errorCode, String errorMsg) {
        LOG.error("Error id: {}; Code: {}: {}", id, errorCode, errorMsg);
    }

    //! [error]
    @Override
    public void connectionClosed() {
        LOG.info("Connection closed");
    }

}
