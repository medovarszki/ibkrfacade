package hu.auxin.ibkrgateway;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IbkrGatewayApplication {

    private static final Logger LOG = LogManager.getLogger(IbkrGatewayApplication.class);

    public static void main(String[] args) {
        //startup spring
        TWS tws = SpringApplication.run(IbkrGatewayApplication.class, args).getBean(TWS.class);

        //connect to TWS
        tws.connect();
    }
}
