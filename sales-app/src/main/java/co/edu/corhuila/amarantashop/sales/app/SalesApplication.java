package co.edu.corhuila.amarantashop.sales.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The composition root: the one place that knows every concrete type and every limit. */
@SpringBootApplication
@EnableScheduling
public class SalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesApplication.class, args);
    }
}
