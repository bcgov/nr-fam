package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.repository.FamUserTypeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-deploy sanity check: can the service reach the database, and has it been
 * migrated?
 *
 * <p>Port of {@code router_smoke_test.py}, including its unusual 417 for
 * "reached the database but it is not set up" - a healthy but unmigrated
 * database is a failed deployment, not a healthy one. Unauthenticated, as
 * upstream.
 *
 * <p><b>Checks reference data, not user data.</b> This used to count
 * {@code fam_user}, which was only ever non-zero because a local seed had been
 * applied. That made a correctly migrated, freshly deployed environment report
 * 417 until somebody signed in - the smoke test failing for the one reason it
 * should not, an empty user table.
 *
 * <p>{@code fam_user_type_code} is populated by the baseline migration itself,
 * so it is non-empty exactly when the database has been migrated, and never
 * depends on anybody having used the application.
 */
@RestController
@RequestMapping("/smoke_test")
@Tag(name = "Smoke Test")
@RequiredArgsConstructor
public class SmokeTestController {

  private final FamUserTypeRepository userTypeRepository;

  @GetMapping
  @Operation(operationId = "smoke_test",
      summary = "Verify database connectivity and that migrations have run")
  public ResponseEntity<Void> smokeTest() {
    // A successful count proves connectivity; a non-zero one proves the baseline
    // ran, since these rows are inserted by the migration that creates the table.
    long referenceRows = userTypeRepository.count();
    return referenceRows == 0
        ? ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build()
        : ResponseEntity.ok().build();
  }
}
