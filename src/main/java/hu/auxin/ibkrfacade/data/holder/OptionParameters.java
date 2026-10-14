package hu.auxin.ibkrfacade.data.holder;

import java.util.Set;

/**
 * Result of a securityDefinitionOptionalParameter callback (one per exchange).
 */
public record OptionParameters(String exchange, String tradingClass, String multiplier, Set<String> expirations,
                               Set<Double> strikes) {
}
