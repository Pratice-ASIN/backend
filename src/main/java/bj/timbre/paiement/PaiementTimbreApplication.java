package bj.timbre.paiement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaiementTimbreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaiementTimbreApplication.class, args);
    }
}
