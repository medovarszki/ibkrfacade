package hu.auxin.ibkrfacade.tws;

/**
 * Published when TWS reports that its connectivity to the IB servers was restored, but the market data
 * subscriptions were lost (error code 1101), so they have to be requested again on the same socket session.
 */
public record TwsDataLostEvent() {
}
