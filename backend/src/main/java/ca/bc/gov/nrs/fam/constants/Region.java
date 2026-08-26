package ca.bc.gov.nrs.fam.constants;

import lombok.Getter;

/**
 * BC natural resource region, used to scope roles marked region-scoped.
 */
@Getter
public enum Region {

  NORTHEAST("NORTHEAST", "Northeast", false),
  OMINECA("OMINECA", "Omineca", false),
  SKEENA("SKEENA", "Skeena", false),
  CARIBOO("CARIBOO", "Cariboo", false),
  KOOTENAY_BOUNDARY("KOOTENAY_BOUNDARY", "Kootenay-Boundary", false),
  THOMPSON_OKANAGAN("THOMPSON_OKANAGAN", "Thompson-Okanagan", false),
  SOUTH_COAST("SOUTH_COAST", "South Coast", false),
  WEST_COAST("WEST_COAST", "West Coast", false);

  private final String regionCode;

  private final String regionName;

  private final boolean expired;

  Region(String regionCode, String regionName, boolean expired) {
    this.regionCode = regionCode;
    this.regionName = regionName;
    this.expired = expired;
  }

  /**
   * Look up by region code, case-insensitively.
   *
   * <p>Empty rather than throwing for an unknown code: it is reached from a
   * bulk upload, where an unrecognised district is one bad row rather than a
   * bad file. The caller turns it into a message naming the code.
   *
   * <p>Matches on the code the enum carries rather than on {@link #name()} - they
   * happen to be identical today, and a district renamed in code should not
   * silently stop resolving the value stored in existing role names.
   */
  public static java.util.Optional<Region> fromRegionCode(String regionCode) {
    if (regionCode == null || regionCode.isBlank()) {
      return java.util.Optional.empty();
    }
    String wanted = regionCode.trim();
    return java.util.Arrays.stream(values())
        .filter(district -> district.regionCode.equalsIgnoreCase(wanted))
        .findFirst();
  }
}
