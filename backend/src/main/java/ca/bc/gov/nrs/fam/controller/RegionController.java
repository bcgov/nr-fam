package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.Region;
import ca.bc.gov.nrs.fam.dto.FamRegionDto;
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

/** Companion to {@link DistrictController}, for the region scope dimension. */
@RestController
@RequestMapping("/regions")
@Tag(name = "FAM Regions")
@RequiredArgsConstructor
public class RegionController {

  private final AuthorizationService authorizationService;

  /**
   * Every BC natural resource region.
   *
   * <p>Returns the full set, expired ones included, and leaves filtering to the
   * caller - the same contract as the districts route. The picker hides expired
   * regions so none can be granted, while a permission that already references
   * one still renders.
   */
  @GetMapping
  @Operation(operationId = "get_regions",
      summary = "List the BC natural resource regions available for scoping roles")
  public List<FamRegionDto> getRegions(Requester requester) {

    // The caller must administer at least one application, matching the guard on
    // the districts route.
    authorizationService.authorize(requester);

    return Arrays.stream(Region.values()).map(FamRegionDto::from).toList();
  }
}
