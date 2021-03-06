package hu.auxin.ibkrfacade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class IbkrFacadeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IbkrFacadeApplication.class, args);
    }
}
