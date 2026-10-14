package hu.auxin.ibkrfacade.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI ibkrFacadeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("IBKR Facade")
                        .version("1.0")
                        .description("""
                                REST API over the Interactive Brokers TWS API (IB Gateway).

                                * Errors are RFC 9457 problem details with a machine readable `code` property, \
                                eg. `MARKET_DATA_UNAVAILABLE`, `LIVE_TRADING_DISABLED`, `CONTRACT_NOT_FOUND`, \
                                `TWS_NOT_CONNECTED`.
                                * `POST /order` is idempotent by `client_order_id`.
                                * When the API token authentication is enabled, send `Authorization: Bearer <token>`. \
                                The read token can call the read-only endpoints, `POST /order` and \
                                `DELETE /orders/{orderId}` need the write token.
                                * Send `traceparent` (W3C) or `X-Trace-Id` to correlate the logs, the trace id is \
                                returned in `X-Trace-Id`.""")
                        .contact(new Contact()
                                .name("Mihaly Medovarszki")
                                .email("mihaly@medovarszki.hu")
                                .url("https://github.com/medovarszki/ibkrfacade")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("Read or write API token (if ibkr.security.enabled=true)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
