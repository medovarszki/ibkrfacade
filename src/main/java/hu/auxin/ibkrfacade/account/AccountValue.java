package hu.auxin.ibkrfacade.account;

/**
 * One value of an accountSummary callback
 */
public record AccountValue(String account, String tag, String value, String currency) {
}
