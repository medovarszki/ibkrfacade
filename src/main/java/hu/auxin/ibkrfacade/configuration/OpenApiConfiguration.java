package hu.auxin.ibkrfacade.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI springShopOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("IBKR Facade")
                .description("Web endpoints for accessing Interactive Brokers TWS API")
                .contact(new Contact()
                        .name("Mihaly Medovarszki")
                        .email("mihaly@medovarszki.hu")
                        .url("https://github.com/medovarszki/ibkrfacade")
                ));
    }
}
