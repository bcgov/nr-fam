package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.PagedResults;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamUserRoleXrefRepository;
import ca.bc.gov.nrs.fam.repository.UserRoleAssignmentSpecs;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of {@code crud_application.py}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

  private final FamApplicationRepository applicationRepository;
  private final FamUserRoleXrefRepository userRoleXrefRepository;
  private final FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;
  private final ForestClientEnrichmentService forestClientEnrichmentService;
  private final FamDtoMapper mapper;

  @Transactional(readOnly = true)
  public FamApplication getApplication(Long applicationId) {
    return applicationRepository.findById(applicationId).orElse(null);
  }

  /**
   * Load an application or fail with the error code the frontend expects.
   *
   * <p>Port of the not-found branch of {@code crud_utils.is_app_admin}.
   */
  @Transactional(readOnly = true)
  public FamApplication requireApplication(Long applicationId) {
    return applicationRepository.findById(applicationId)
        .orElseThrow(() -> FamHttpException.badRequest(
            ErrorCode.INVALID_APPLICATION_ID, "Application ID " + applicationId + " not found"));
  }

  /**
   * Whether the requester holds {@code <APPLICATION_NAME>_ADMIN} on their token.
   *
   * <p>Port of {@code crud_utils.is_app_admin}. Fails with
   * {@code invalid_application_id} if the application does not exist, rather than
   * returning false, so a bad id is not reported as a permission problem.
   */
  @Transactional(readOnly = true)
  public boolean isAppAdmin(Long applicationId, Requester requester) {
    FamApplication application = requireApplication(applicationId);
    return requester.isAdminOf(application.getApplicationName());
  }

  /**
   * Paged user-role assignments for an application, scoped to what the requester
   * is allowed to see, with forest client names filled in.
   *
   * <p>Port of {@code crud_application.get_application_role_assignments} together
   * with the {@code post_sync_forest_clients_dec} decorator that wrapped it. The
   * enrichment soft-fails, so an unavailable Forest Client API costs client names
   * but not the response.
   */
  @Transactional(readOnly = true)
  public PagedResults<FamApplicationUserRoleAssignmentGetDto> getApplicationRoleAssignments(
      Long applicationId, Requester requester, UserRolePageParams pageParams) {

    log.debug("Querying user role assignments for application {} by requester {}",
        applicationId, requester.userName());

    Specification<FamUserRoleXref> spec = buildSpecification(applicationId, requester, pageParams);

    Page<FamUserRoleXref> page = userRoleXrefRepository.findAll(
        spec, PageRequest.of(pageParams.toZeroBasedPage(), pageParams.getPageSize()));

    List<FamApplicationUserRoleAssignmentGetDto> results =
        page.getContent().stream().map(mapper::toAssignmentDto).toList();

    results = forestClientEnrichmentService.withClientNames(
        results, requireApplication(applicationId));

    log.debug("Found {} user role assignments (total {})", results.size(), page.getTotalElements());
    return PagedResults.from(page, results);
  }

  /**
   * The same privilege-scoped query without paging, for the CSV export.
   *
   * <p>Upstream deliberately skipped the forest-client name sync here; this does
   * too, so the export shows client numbers rather than names.
   */
  @Transactional(readOnly = true)
  public List<FamApplicationUserRoleAssignmentGetDto> getApplicationRoleAssignmentsNoPaging(
      Long applicationId, Requester requester) {

    // Search and sort defaults only; there is no user input to apply here.
    UserRolePageParams defaults = new UserRolePageParams();
    Specification<FamUserRoleXref> spec = buildSpecification(applicationId, requester, defaults);

    return userRoleXrefRepository.findAll(spec).stream().map(mapper::toAssignmentDto).toList();
  }

  private Specification<FamUserRoleXref> buildSpecification(
      Long applicationId, Requester requester, UserRolePageParams pageParams) {

    boolean appAdmin = isAppAdmin(applicationId, requester);
    List<Long> managedRoleIds = appAdmin
        ? List.of()
        : accessControlPrivilegeRepository.findManagedRoleIds(requester.userId(), applicationId);

    return UserRoleAssignmentSpecs.forApplication(
        applicationId, requester, appAdmin, managedRoleIds, pageParams);
  }
}
