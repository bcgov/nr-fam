package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.SortOrder;
import ca.bc.gov.nrs.fam.constants.UserRoleSortBy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Query parameters for the paged user-role listing.
 *
 * <p>The external names ({@code pageNumber}, {@code pageSize}, {@code sortBy},
 * {@code sortOrder}) are what the frontend sends, so they are bound explicitly
 * rather than derived from the Java field names.
 */
@Schema(name = "UserRolePageParams")
public class UserRolePageParams {

  @Min(FamConstants.MIN_PAGE)
  @Schema(description = "Page number", defaultValue = "1")
  private Integer pageNumber = FamConstants.MIN_PAGE;

  @Min(FamConstants.MIN_PAGE_SIZE)
  @Max(FamConstants.MAX_PAGE_SIZE)
  @Schema(description = "Number of records per page", defaultValue = "50")
  private Integer pageSize = FamConstants.DEFAULT_PAGE_SIZE;

  @Size(min = FamConstants.SEARCH_FIELD_MIN_LENGTH, max = FamConstants.SEARCH_FIELD_MAX_LENGTH)
  @Schema(nullable = true, description = "Search by keyword")
  private String search;

  // Declared before sortBy: the generated client passes these
  // positionally, and upstream emitted sortOrder first.
  @Schema(description = "Column sorting order")
  private SortOrder sortOrder = SortOrder.DESC;

  @Schema(description = "Column to be sorted by")
  private UserRoleSortBy sortBy = UserRoleSortBy.defaultSort();

  public Integer getPageNumber() {
    return pageNumber;
  }

  public void setPageNumber(Integer pageNumber) {
    this.pageNumber = pageNumber == null ? FamConstants.MIN_PAGE : pageNumber;
  }

  public Integer getPageSize() {
    return pageSize;
  }

  public void setPageSize(Integer pageSize) {
    this.pageSize = pageSize == null ? FamConstants.DEFAULT_PAGE_SIZE : pageSize;
  }

  public String getSearch() {
    return search;
  }

  public void setSearch(String search) {
    this.search = search;
  }

  public UserRoleSortBy getSortBy() {
    return sortBy;
  }

  public void setSortBy(UserRoleSortBy sortBy) {
    this.sortBy = sortBy == null ? UserRoleSortBy.defaultSort() : sortBy;
  }

  public SortOrder getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(SortOrder sortOrder) {
    this.sortOrder = sortOrder == null ? SortOrder.DESC : sortOrder;
  }

  /** Spring Data pages are 0-based; the API contract is 1-based. */
  public int toZeroBasedPage() {
    return Math.max(0, pageNumber - 1);
  }
}
