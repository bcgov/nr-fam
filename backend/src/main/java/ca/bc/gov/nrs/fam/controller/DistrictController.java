package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.District;
import ca.bc.gov.nrs.fam.dto.FamDistrictDto;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_district.py}. */
@RestController
@RequestMapping("/districts")
@Tag(name = "FAM Districts")
@RequiredArgsConstructor
public class DistrictController {

  private final AuthorizationService authorizationService;

  /**
   * Every BC natural resource district.
   *
   * <p>Returns the full set, expired ones included, and leaves filtering to the
   * caller. The picker hides expired districts so none can be granted, while a
   * permission that already references one still renders.
   */
  @GetMapping
  @Operation(operationId = "get_districts",
      summary = "List the BC natural resource districts available for scoping roles")
  public List<FamDistrictDto> getDistricts(Requester requester) {

    // General check: the caller must administer at least one application. Matches
    // the router-level guard upstream wired this route with.
    authorizationService.authorize(requester);

    return Arrays.stream(District.values()).map(FamDistrictDto::from).toList();
  }
}
