package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-deploy sanity check: can the service reach the database and does it hold
 * seeded application data?
 *
 * <p>Port of {@code router_smoke_test.py}, including its unusual 417 for "reached
 * the database but found no applications" - a healthy but unseeded database is a
 * failed deployment, not a healthy one. Unauthenticated, as upstream.
 */
@RestController
@RequestMapping("/smoke_test")
@Tag(name = "Smoke Test")
@RequiredArgsConstructor
public class SmokeTestController {

  private final FamApplicationRepository applicationRepository;

  @GetMapping
  @Operation(operationId = "smoke_test", summary = "Verify database connectivity and seeded application data")
  public ResponseEntity<Void> smokeTest() {
    long applications = applicationRepository.count();
    return applications == 0
        ? ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build()
        : ResponseEntity.ok().build();
  }
}
