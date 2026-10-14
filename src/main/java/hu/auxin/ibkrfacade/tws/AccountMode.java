package hu.auxin.ibkrfacade.tws;

import java.util.Collection;

/**
 * Type of the account(s) the facade is connected to.
 */
public enum AccountMode {
    PAPER, LIVE, UNKNOWN;

    /**
     * IBKR paper trading account ids start with "D" (DU..., DF...), live accounts don't.
     * If there is at least one live account among the managed ones, the connection is treated as live.
     */
    public static AccountMode of(Collection<String> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return UNKNOWN;
        }
        return accounts.stream().allMatch(account -> account.startsWith("D")) ? PAPER : LIVE;
    }
}
