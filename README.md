# IBKR Facade
## Interactive Brokers API wrapper and trading toolbox 
This project is built around the [Interactive Broker's TWS library](https://interactivebrokers.github.io/tws-api/) for Java. If you have a TWS or IB Gateway running, you can use this as a market data source for time series analysis, or even as a market gateway for trading bots.

**In this project you'll find:**

- The basic functions of the TWS API exposed to a Rest API
    - Searching in Interactive Brokers' instrument master data
    - Subscribe to market data for certain instruments
    - Create and manager orders
- Market data streamed into a Redis server from where you can do it whatever you want (eg.: using as a data source by creating a pub-sub message queue; or stream it to a time series database for further analitycs, build historical datasets, etc.)
- Built-in sample trading strategy (not a real working strategy, just an example of course), which is based on periodical time series analysis looking for trading signal and placing orders.


## Prerequisites
As step zero, you need an account at Interactive Brokers.

### TWS or IB gateway
[Trading Workstation](https://www.interactivebrokers.com/en/index.php?f=14099#tws-software) (TWS) or [IB Gateway](https://www.interactivebrokers.com/en/?f=/en/trading/ibgateway-stable.php) has to be installed in order to provide access to Interactive Brokers' infrastructure. If you are using TWS, make sure that the API connection is enabled, and if you want to placing orders through the library, the connection is not restricted to "read-only".

**See more:** [TWS API initial setup](https://interactivebrokers.github.io/tws-api/initial_setup.html)

### Market data subscription
In order to access market data you'll need an account at Interactive Brokers with subscription to those markets and instruments you need. If you want to use a paper trading account first (which I highly recommend) instead of your live account, you need to share your market subscriptions between your accounts.

**See:** [Sharing market data subscription](https://interactivebrokers.github.io/tws-api/market_data.html#paper_sharing)

### TWS API
You have to download the TWS API from https://interactivebrokers.github.io for Java. It contains a TwsApi.jar file which you need to have on your classpath. (You can find a working version of it in the /lib folder of this repository.) This is a legacy JAR maintained by Interactive Brokers and you cannot find it in the central Maven repository, so if you want to build this project with Maven, you need to manually add it to your local artifact repository (see pom.xml dependencies section), or make it available on the classpath some other way.

### Redis server
Redis is a powerful and extremely fast tool for storing market data. If you have a Redis server with RedisTimeSeries extension up and running, you can configure its access through the application.properties file, then the library will send the market data to the Redis for the subscribed instruments.


## How to use
1. Checkout the project
2. Add the `lib/TwsApi.jar` to the classpath from your IDE or alternatively publish it to a maven repository. Don't forget to modify `pom.xml` if needed!
3. Check your `application.properties` for:
   - TWS or IB gateway host and port number
   - URL settings for REST API
   - Redis server connection 
4. Build the project with maven
5. If you have your setup up and running, IBKR Facade should connect to Interactive Brokers automatically.

You will be able efficiently use the software only if you are familiar with the terminology and concepts used by Interactive Brokers, especially [Contracts](https://interactivebrokers.github.io/tws-api/contracts.html) and `conid` as the unique IB contract identifier.

**See:** [Search Interactive Brokers Contract Database](https://www.interactivebrokers.com/en/index.php?f=463) 

## Use cases

### REST API
Every basic function needed for using the system is exposed through a REST API, such as:
- Contract search
- Subscription to the market feed for certain contracts
- Getting price information
- Placing orders
- Getting positions

You can change the port number (8082 by default) and context-path (default is `/ibkr`) from the `application.properties` file.

The endpoints are documented using [Swagger OpenAPI](http://swagger.io) annotations. After you started the application you should find the documentation under the following URL: http://localhost:8082/ibkr/swagger-ui/

### Market data analysis (Redis TimeSeries)
If you have your Redis ready you can subscribe to market data feed of any instrument available on Interactive Brokers through your brokerage account.

From Java, you can use the `ContractManagerService.subscribe()` method or via HTTP you can use the `/subscribe` endpoint. If everything works as expected, the Contract itself should be saved to Redis under the conid of the Contract as key, and two time series should be created for storing the price information.

If you want to change or extend the functionality of this, you need to extend the `TimeSeriesHandler` class. You can utilize the full power of Redis from calculating OHLC data automatically to using it as a pub/sub service, it's all up to you. 

Once you have subscribed to an instrument, the price stream (bid/ask changes) will be written into the Redis by the following pattern:
- A `ContractHolder` will be created and saved by it's `conid` used as a key
- The `ContractHolder` contains the `Contract` descriptor provided by Interactive Brokers
- The `ContractHolder` has a `streamRequestId` which is used for identifying the communication "stream" with the TWS itself. When you send a request to Interactive Brokers through TWS with a unique number (the IB documentation refers to this identifier as streamId or tickId), this number will be used in the response method calls. We save this number into the `ContractHolder` as `streamRequestId`.
- The time series will be available under the following key format: `stream:[streamRequestId]:[BID/ASK]` so basically two series are stored per subscribed instrument: one for the bids and one for the asks.

**See:** [RedisTimeSeries](https://redis.io/docs/stack/timeseries)

### Trading strategy automation
Since you have hands-on market data, with the methods of `OrderManagerService`, `ContractManagerService` and `PositionManagerService` there is no predetermined way how to implement a trading strategy, the only limit is your imagination :)  

You can find an example implementation in the `strategy` package, which periodically checks the prices of Apple stock from Redis looking for a trading signal. Once the trade performed (you have an open position) it checks the price movements for a possible exit.

**Important: This is not a real strategy. Don't even think about using it real conditions!**