# IBKR gateway, an Interactive Brokers TWS API wrapper	
This project is built around the [Interactive Broker's TWS library](https://interactivebrokers.github.io/tws-api/) for Java. If you have a TWS running, you can use this as a market data source for other projects, or even as a basic "trading bot" which is able to send orders to the market. 

Features implemented in this project:

 - Rest API for:
	 - Searching for instruments on markets available through Interactive Brokers
	 - Subscribe to market data for certain instruments
	 - Create / manager orders
 - Market data streamed into a Redis server from where you can do it whatever you want (eg.: using as a data source by creating a pub-sub message queue; or stream it to a time series database for further analitycs, build historical datasets, etc.)

## Prerequisites

### Interactive Brokers account
For having acces to market data you will need an account at Interactive Brokers with subscription to those markets you need.
### Trading Workstation (TWS)
You need a running TWS application available for external connections.
### A running Redis server
