package ca.bc.gov.nrs.fam;

import ca.bc.gov.nrs.fam.configuration.NativeRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Forest Access Management (FAM) API.
 *
 * <p>A single service covering what upstream FAM split across two AWS Lambda
 * FastAPI applications: the app-access-control API ({@code server/backend}) and
 * the admin-management API ({@code server/admin_management}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@ImportRuntimeHints(NativeRuntimeHints.class)
public class FamApiApplication {

  /**
   * The container's health check arrives here too.
   *
   * <p>The image is a single native binary: there is no {@code java} to launch a
   * second class with, and distroless has no shell, no curl and no wget. So the
   * binary answers to one argument and probes itself - see {@link HealthCheck} -
   * which costs a process that starts in milliseconds rather than a JVM that
   * would have reserved a share of the container's memory to do it.
   *
   * <p>OpenShift ignores {@code HEALTHCHECK} and runs its own HTTP probes; this
   * is for anyone running the image directly.
   */
  public static final String HEALTH_CHECK_ARGUMENT = "healthcheck";

  public static void main(String[] args) {
    if (args.length > 0 && HEALTH_CHECK_ARGUMENT.equals(args[0])) {
      HealthCheck.main(args);
      return;
    }
    SpringApplication.run(FamApiApplication.class, args);
  }
}
