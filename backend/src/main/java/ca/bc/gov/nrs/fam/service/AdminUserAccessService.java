package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.AppEnv;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.dto.AdminUserAccessResponse;
import ca.bc.gov.nrs.fam.dto.FamApplicationGrantDto;
import ca.bc.gov.nrs.fam.dto.FamAuthGrantDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientBase;
import ca.bc.gov.nrs.fam.dto.FamGrantDetailDto;
import ca.bc.gov.nrs.fam.dto.FamRoleGrantDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationAdminRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out everything a signed-in administrator is allowed to do.
 *
 * <p>Port of {@code admin_management/api/app/services/admin_user_access_service.py}.
 *
 * <p>The frontend drives its whole navigation and permission model from this one
 * response, so the shape matters more than most: a capacity the user does not
 * hold is <em>absent</em> from {@code access}, never present-and-empty.
 *
 * <p>FAM's data model does not name the three admin kinds; they are derived from
 * which tables the user appears in. See {@link AdminRoleAuthGroup}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserAccessService {

  private static final List<String> APP_ENV_SUFFIXES = List.of("_DEV", "_TEST", "_PROD");

  private final FamApplicationAdminRepository applicationAdminRepository;
  private final FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;
  private final FamApplicationRepository applicationRepository;
  private final FamRoleRepository roleRepository;

  @Transactional(readOnly = true)
  public AdminUserAccessResponse getAccessGrants(Long userId) {
    List<FamApplication> administeredApplications =
        applicationAdminRepository.findAdministeredApplications(userId);

    boolean isFamAdmin = administeredApplications.stream()
        .anyMatch(app -> FamConstants.APPLICATION_FAM.equals(app.getApplicationName()));

    // FAM itself is what confers FAM_ADMIN; it is not one of the applications an
    // APP_ADMIN administers.
    List<FamApplication> appAdminApplications = administeredApplications.stream()
        .filter(app -> !FamConstants.APPLICATION_FAM.equals(app.getApplicationName()))
        .toList();

    List<FamRole> delegatedAdminRoles =
        accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(userId);

    List<FamAuthGrantDto> grants = new ArrayList<>();

    if (isFamAdmin) {
      grants.add(famAdminGrant());
    }
    if (!appAdminApplications.isEmpty()) {
      grants.add(appAdminGrant(appAdminApplications));
    }
    if (!delegatedAdminRoles.isEmpty()) {
      grants.add(delegatedAdminGrant(delegatedAdminRoles));
    }

    log.debug("Resolved {} access grant group(s) for user {}", grants.size(), userId);
    return new AdminUserAccessResponse(grants);
  }

  /** A FAM admin may administer every application, so the grant lists them all. */
  private FamAuthGrantDto famAdminGrant() {
    List<FamGrantDetailDto> details = applicationRepository.findAll().stream()
        // No roles: the authority is over the application as a whole.
        .map(application -> new FamGrantDetailDto(toApplicationGrant(application), null))
        .toList();

    return new FamAuthGrantDto(AdminRoleAuthGroup.FAM_ADMIN, details);
  }

  /** An app admin may grant any base role of the applications they administer. */
  private FamAuthGrantDto appAdminGrant(List<FamApplication> applications) {
    List<FamGrantDetailDto> details = applications.stream()
        .map(application -> new FamGrantDetailDto(
            toApplicationGrant(application),
            roleRepository.findBaseRolesByApplicationId(application.getApplicationId()).stream()
                .map(AdminUserAccessService::toRoleGrant)
                .toList()))
        .toList();

    return new FamAuthGrantDto(AdminRoleAuthGroup.APP_ADMIN, details);
  }

  /**
   * A delegated admin may grant only the specific roles they hold privilege over.
   *
   * <p>Client-scoped privileges arrive as one child role per forest client. They
   * are collapsed back onto the abstract parent, so the UI shows one role with a
   * list of clients rather than a row per client.
   */
  private FamAuthGrantDto delegatedAdminGrant(List<FamRole> grantedRoles) {
    // Grouped by application, then within an application by parent role. The
    // repository orders by (application, role) so grouping is stable.
    Map<Long, List<FamRole>> byApplication = new LinkedHashMap<>();
    for (FamRole role : grantedRoles) {
      byApplication
          .computeIfAbsent(role.getApplication().getApplicationId(), k -> new ArrayList<>())
          .add(role);
    }

    List<FamGrantDetailDto> details = new ArrayList<>();

    byApplication.forEach((applicationId, roles) -> {
      FamApplication application = roles.get(0).getApplication();

      // Null key = roles granted directly. Non-null = child roles sharing a parent.
      Map<Long, List<FamRole>> byParent = new LinkedHashMap<>();
      for (FamRole role : roles) {
        Long parentId = role.getParentRole() == null ? null : role.getParentRole().getRoleId();
        byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(role);
      }

      List<FamRoleGrantDto> roleGrants = new ArrayList<>();
      byParent.forEach((parentId, group) -> {
        if (parentId == null) {
          group.stream().map(AdminUserAccessService::toRoleGrant).forEach(roleGrants::add);
        } else {
          FamRole parentRole = group.get(0).getParentRole();
          List<FamForestClientBase> clients = group.stream()
              .filter(child -> child.getForestClient() != null)
              .map(child -> new FamForestClientBase(
                  null, child.getForestClient().getForestClientNumber()))
              .toList();
          roleGrants.add(toRoleGrant(parentRole).withForestClients(clients));
        }
      });

      details.add(new FamGrantDetailDto(toApplicationGrant(application), roleGrants));
    });

    return new FamAuthGrantDto(AdminRoleAuthGroup.DELEGATED_ADMIN, details);
  }

  private static FamApplicationGrantDto toApplicationGrant(FamApplication application) {
    return new FamApplicationGrantDto(
        application.getApplicationId(),
        // The environment is reported separately in `env`, so the suffix is
        // redundant noise in the display name.
        removeAppEnvSuffix(application.getApplicationName()),
        application.getApplicationDescription(),
        AppEnv.fromCode(application.getAppEnvironment()).orElse(null));
  }

  private static FamRoleGrantDto toRoleGrant(FamRole role) {
    return new FamRoleGrantDto(
        role.getRoleId(),
        role.getRoleName(),
        role.getDisplayName(),
        role.getRolePurpose(),
        RoleType.fromCode(role.getRoleTypeCode()).orElse(null),
        null);
  }

  /** FOM_DEV becomes FOM. Port of {@code utils.remove_app_env_suffix}. */
  static String removeAppEnvSuffix(String applicationName) {
    if (applicationName == null) {
      return null;
    }
    for (String suffix : APP_ENV_SUFFIXES) {
      if (applicationName.endsWith(suffix)) {
        return applicationName.substring(0, applicationName.length() - suffix.length());
      }
    }
    return applicationName;
  }
}
