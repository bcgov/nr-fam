package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.IdpType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Query parameters for the external user search.
 *
 * <p>Paging is 1-indexed here, unlike Spring's 0-indexed {@code Pageable} - and
 * unlike nothing else in FAM, since the internal paged endpoints are 1-indexed
 * too. The page size ceiling is lower than the internal one: this is a published
 * API for other applications, so it is capped at 100 rather than 100,000.
 */
@Schema(name = "ExtUserSearchParams")
public class ExtUserSearchParams {

  @Min(FamConstants.EXT_MIN_PAGE)
  @Schema(description = "Page number - 1 index")
  private Integer page = FamConstants.EXT_MIN_PAGE;

  @Min(FamConstants.EXT_MIN_PAGE_SIZE)
  @Max(FamConstants.EXT_MAX_PAGE_SIZE)
  @Schema(description = "Number of records per page")
  private Integer size = FamConstants.EXT_DEFAULT_PAGE_SIZE;

  @Schema(description = "Identity provider type. Available values: IDIR, BCEID, BCSC")
  private IdpType idpType;

  @Size(max = FamConstants.EXT_MAX_IDP_USERNAME_LEN)
  @Schema(description = "Username from the identity provider")
  private String idpUsername;

  @Size(max = FamConstants.EXT_MAX_FIRST_NAME_LEN)
  @Schema(description = "User's first name")
  private String firstName;

  @Size(max = FamConstants.EXT_MAX_LAST_NAME_LEN)
  @Schema(description = "User's last name")
  private String lastName;

  @Size(max = FamConstants.EXT_MAX_ROLE_LIST_LEN)
  @Schema(description = "Role codes to filter by, e.g. ILCR_SUBMITTER")
  private List<@Size(max = FamConstants.EXT_MAX_ROLE_LEN) String> role;

  public Integer getPage() {
    return page;
  }

  public void setPage(Integer page) {
    this.page = page == null ? FamConstants.EXT_MIN_PAGE : page;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size == null ? FamConstants.EXT_DEFAULT_PAGE_SIZE : size;
  }

  public IdpType getIdpType() {
    return idpType;
  }

  public void setIdpType(IdpType idpType) {
    this.idpType = idpType;
  }

  public String getIdpUsername() {
    return idpUsername;
  }

  public void setIdpUsername(String idpUsername) {
    this.idpUsername = idpUsername;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public List<String> getRole() {
    return role;
  }

  public void setRole(List<String> role) {
    this.role = role;
  }

  /** Spring Data pages are 0-based; this API is 1-based. */
  public int toZeroBasedPage() {
    return Math.max(0, page - 1);
  }
}
