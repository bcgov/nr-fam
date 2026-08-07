package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.FamAppAdminCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamAppAdminGetResponse;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamApplicationAdmin;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamApplicationAdminRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granting and revoking application-administrator access.
 *
 * <p>Port of {@code admin_management/.../application_admin_service.py}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationAdminService {

  private final FamApplicationAdminRepository applicationAdminRepository;
  private final ApplicationService applicationService;
  private final UserService userService;
  private final AdminPermissionAuditWriteService permissionAuditWriteService;
  private final FamDtoMapper mapper;

  @Transactional(readOnly = true)
  public List<FamAppAdminGetResponse> getApplicationAdmins() {
    return applicationAdminRepository.findAllWithUserAndApplication().stream()
        .map(mapper::toAppAdminResponse)
        .toList();
  }

  /**
   * Administrators of one application.
   *
   * @param excludeUserId the requester, who is omitted from the list - the UI does
   *     not offer you the option of removing your own access here
   */
  @Transactional(readOnly = true)
  public List<FamAppAdminGetResponse> getApplicationAdminsByApplication(
      Long applicationId, Long excludeUserId) {
    return applicationAdminRepository
        .findByApplicationExcludingUser(applicationId, excludeUserId).stream()
        .map(mapper::toAppAdminResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public FamApplicationAdmin getById(Long applicationAdminId) {
    return applicationAdminRepository.findById(applicationAdminId)
        .orElseThrow(() -> FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Application admin id " + applicationAdminId + " does not exist."));
  }

  /**
   * Make a user an administrator of an application.
   *
   * @throws FamHttpException 409 if they already are. Upstream chose a conflict
   *     over silently succeeding, so the UI can say so rather than implying it
   *     just made the change.
   */
  @Transactional
  public FamAppAdminGetResponse createApplicationAdmin(
      FamAppAdminCreateRequest request, TargetUser targetUser, Requester requester) {

    log.debug("Assigning application admin for application {}", request.applicationId());

    FamUser famUser = userService.findOrCreate(
        request.userTypeCode().getCode(), request.userName(), request.userGuid(),
        requester.oidcUserId());
    famUser = userService.updateFromVerifiedTargetUser(
        famUser.getUserId(), targetUser, requester.oidcUserId());

    if (applicationAdminRepository.existsByUserUserIdAndApplicationApplicationId(
        famUser.getUserId(), request.applicationId())) {
      throw FamHttpException.conflict(ErrorCode.INVALID_OPERATION, "User is admin already.");
    }

    FamApplication application = applicationService.requireApplication(request.applicationId());

    FamApplicationAdmin admin = new FamApplicationAdmin();
    admin.setUser(famUser);
    admin.setApplication(application);
    admin.setCreateUser(requester.oidcUserId());

    FamApplicationAdmin saved = applicationAdminRepository.save(admin);

    permissionAuditWriteService.storeApplicationAdminGranted(requester, famUser, saved);

    log.debug("Application admin assignment {} created", saved.getApplicationAdminId());
    return mapper.toAppAdminResponse(saved);
  }

  /** Revoke application-admin access. The audit record is written before the delete. */
  @Transactional
  public void deleteApplicationAdmin(Requester requester, Long applicationAdminId) {
    FamApplicationAdmin admin = getById(applicationAdminId);

    permissionAuditWriteService.storeApplicationAdminRevoked(requester, admin);

    applicationAdminRepository.delete(admin);
    log.debug("Application admin assignment {} deleted", applicationAdminId);
  }
}
