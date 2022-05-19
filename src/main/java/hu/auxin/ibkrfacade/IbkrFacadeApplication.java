package hu.auxin.ibkrfacade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class IbkrFacadeApplication {

    public static void main(String[] args) {
        //startup spring
        TWS tws = SpringApplication.run(IbkrFacadeApplication.class, args).getBean(TWS.class);

        //connect to TWS
        tws.connect();
    }
}
