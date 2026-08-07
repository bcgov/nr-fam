package ca.bc.gov.nrs.fam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Forest Access Management (FAM) API.
 *
 * <p>A single service covering what upstream FAM split across two AWS Lambda
 * FastAPI applications: the app-access-control API ({@code server/backend}) and
 * the admin-management API ({@code server/admin_management}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FamApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(FamApiApplication.class, args);
  }
}
