package ca.bc.gov.nrs.fam.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a scope reads once it has been pulled back out of a role name.
 *
 * <p>The name is the only record of what a grant covers, so what comes out of it
 * is a code. Codes are what applications authorise on and are right to keep -
 * but {@code KOOTENAY_BOUNDARY} on a chip is a code leaking onto a screen.
 */
@DisplayName("ScopeDto")
class ScopeDtoTest {

  private static ScopeDto of(String type, String value) {
    return ScopeDto.of(new CssRoleNaming.Scope(type, value));
  }

  @Test
  void namesARegion() {
    assertThat(of("REGION", "KOOTENAY_BOUNDARY").label()).isEqualTo("Kootenay-Boundary");
  }

  @Test
  void keepsTheCodeBesideTheName() {
    // The label is for reading; the value is what the grant was made against,
    // and dropping it would lose the thing an application matches on.
    ScopeDto scope = of("REGION", "KOOTENAY_BOUNDARY");

    assertThat(scope.value()).isEqualTo("KOOTENAY_BOUNDARY");
    assertThat(scope.type()).isEqualTo("REGION");
  }

  @Test
  void leavesARetiredRegionAsItsCode() {
    // Nothing in the enum answers for it any more. The code is already in the
    // row and reads well enough on its own.
    assertThat(of("REGION", "NO_SUCH_REGION").label()).isNull();
  }

  @Test
  void doesNotLendARegionsNameToAnotherKindOfScope() {
    // The kind decides, not the value. Without that check a district or an
    // organisation whose code happened to read like a region's would be
    // relabelled with that region's name - a chip claiming something the grant
    // never said.
    assertThat(of("DISTRICT", "CARIBOO").label()).isNull();
    assertThat(of("FOREST_CLIENT", "SKEENA").label()).isNull();
  }

  @Test
  void namesNeitherADistrictNorAnOrganisation() {
    // Both would cost a call per row - a district's name comes from an upstream
    // list, an organisation's from the Forest Client API - and neither code is
    // unreadable: a district code is short and familiar, and an organisation
    // number is what people search by.
    assertThat(of("DISTRICT", "DCC").label()).isNull();
    assertThat(of("FOREST_CLIENT", "00001012").label()).isNull();
  }
}
