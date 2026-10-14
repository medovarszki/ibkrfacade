package hu.auxin.ibkrfacade.observability;

import hu.auxin.ibkrfacade.tws.TwsConnection;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * State of the TWS / IB Gateway connection, shown as "tws" in /actuator/health.
 * UP only if the API session is ready and TWS is connected to the IB servers.
 */
@Component("tws")
public class TwsHealthIndicator implements HealthIndicator {

    private final TwsConnection connection;

    public TwsHealthIndicator(TwsConnection connection) {
        this.connection = connection;
    }

    @Override
    public Health health() {
        boolean ready = connection.isReady();
        boolean farm = connection.hasFarmConnectivity();
        Health.Builder builder = ready && farm ? Health.up() : Health.down();
        builder.withDetail("host", connection.getHost() + ":" + connection.getPort())
                .withDetail("client_id", connection.getClientId())
                .withDetail("connected", connection.isConnected())
                .withDetail("ready", ready)
                .withDetail("ib_server_connectivity", farm)
                .withDetail("account_mode", connection.getAccountMode())
                .withDetail("accounts", connection.getAccounts().stream().map(TwsConnection::maskAccount).toList());
        if (connection.getConnectedSince() != null) {
            builder.withDetail("connected_since", connection.getConnectedSince().toString());
        }
        if (connection.getLastError() != null) {
            builder.withDetail("last_error", connection.getLastError());
        }
        return builder.build();
    }
}
