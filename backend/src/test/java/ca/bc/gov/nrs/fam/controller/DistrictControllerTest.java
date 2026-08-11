package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ca.bc.gov.nrs.fam.constants.District;
import ca.bc.gov.nrs.fam.dto.FamDistrictDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistrictController (port of router_district.py)")
class DistrictControllerTest {

  @Mock private AuthorizationService authorizationService;
  @InjectMocks private DistrictController controller;

  private final Requester requester = Requester.builder().userId(1L).userName("JSMITH").build();

  @Test
  @DisplayName("returns every district, including expired ones")
  void returnsEveryDistrict() {
    // Filtering is the caller's job: an expired district must still resolve on a
    // permission that already references it.
    List<FamDistrictDto> districts = controller.getDistricts(requester);

    assertThat(districts).hasSize(District.values().length);
    assertThat(districts).extracting(FamDistrictDto::orgUnitCode)
        .contains("DCC", "DSE", "DMH");
  }

  @Test
  @DisplayName("maps code, name and expiry off the enum")
  void mapsEnumFields() {
    assertThat(controller.getDistricts(requester))
        .filteredOn(d -> d.orgUnitCode().equals("DCC"))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.orgUnitName()).isEqualTo("Cariboo-Chilcotin Natural Resource District");
          assertThat(d.expired()).isFalse();
        });
  }

  @Test
  @DisplayName("district codes are unique, since the code keys a scoped role name")
  void codesAreUnique() {
    // A duplicate would make two districts collide onto one generated role.
    assertThat(Arrays.stream(District.values()).map(District::getOrgUnitCode).distinct().count())
        .isEqualTo(District.values().length);
  }

  @Test
  @DisplayName("no district code contains the separators used in scoped role names")
  void codesAreSafeInRoleNames() {
    // A scoped role is <role>_<SCOPE_TYPE>-<value>; a code containing '-' or '_'
    // would not parse back out.
    assertThat(Arrays.stream(District.values()).map(District::getOrgUnitCode))
        .allSatisfy(code -> assertThat(code).doesNotContain("-").doesNotContain("_"));
  }

  @Test
  @DisplayName("requires an authorized requester")
  void requiresAuthorization() {
    doThrow(FamHttpException.forbidden(ca.bc.gov.nrs.fam.constants.ErrorCode.INVALID_OPERATION, "not an admin"))
        .when(authorizationService).authorize(any());

    assertThatThrownBy(() -> controller.getDistricts(requester))
        .isInstanceOf(FamHttpException.class);

    verify(authorizationService).authorize(requester);
  }
}
