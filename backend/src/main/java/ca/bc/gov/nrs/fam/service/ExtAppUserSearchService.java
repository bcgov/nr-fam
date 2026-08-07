package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.IdpType;
import ca.bc.gov.nrs.fam.constants.ScopeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.ExtApplicationUserSearchGetDto;
import ca.bc.gov.nrs.fam.dto.ExtPageResultMeta;
import ca.bc.gov.nrs.fam.dto.ExtRoleWithScopeDto;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchPagedResults;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchParams;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.repository.ExtUserSearchSpecs;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User search for downstream applications.
 *
 * <p>Port of {@code crud/services/ext_app_user_search_service.py}. Spec:
 * https://apps.nrs.gov.bc.ca/int/confluence/display/FSAST1/Users+Search+API
 *
 * <p>Results are always confined to the application the caller's token was issued
 * for - an application can only ever see its own users. The controller
 * establishes which application that is; this service takes it as given.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtAppUserSearchService {

  private final FamUserRepository userRepository;

  @Transactional(readOnly = true)
  public ExtUserSearchPagedResults searchUsers(Long applicationId, ExtUserSearchParams params) {

    log.debug("External user search for application {} with {}", applicationId, params);

    Page<FamUser> page = userRepository.findAll(
        ExtUserSearchSpecs.forApplication(applicationId, params),
        PageRequest.of(params.toZeroBasedPage(), params.getSize(),
            // Stable default ordering; the external API exposes no sort control.
            Sort.by(Sort.Direction.ASC, "userName")));

    List<ExtApplicationUserSearchGetDto> users = page.getContent().stream()
        .map(user -> toUserResult(user, applicationId))
        .toList();

    ExtPageResultMeta meta = new ExtPageResultMeta(
        page.getTotalElements(), page.getTotalPages(), params.getPage(), params.getSize());

    log.debug("Returning {} of {} users", users.size(), page.getTotalElements());
    return new ExtUserSearchPagedResults(meta, users);
  }

  private ExtApplicationUserSearchGetDto toUserResult(FamUser user, Long applicationId) {
    return new ExtApplicationUserSearchGetDto(
        user.getFirstName(),
        user.getLastName(),
        user.getUserName(),
        user.getUserGuid(),
        toIdpType(user.getUserTypeCode()),
        buildRoles(user, applicationId));
  }

  /**
   * Collapse the user's roles onto their parents.
   *
   * <p>FAM materialises one child role per forest client. The external contract
   * reports the parent role once with a list of client numbers, so a caller sees
   * "FOM_SUBMITTER for clients A and B" rather than two unrelated-looking roles.
   */
  private List<ExtRoleWithScopeDto> buildRoles(FamUser user, Long applicationId) {
    // Keyed by the reported role name, preserving first-seen order.
    Map<String, RoleAccumulator> byRoleName = new LinkedHashMap<>();

    for (FamUserRoleXref xref : user.getUserRoleXrefs()) {
      FamRole role = xref.getRole();
      // A user may hold roles in several applications; only this one is visible.
      if (role == null || !applicationId.equals(role.getApplication().getApplicationId())) {
        continue;
      }

      boolean scoped = role.getParentRole() != null;
      FamRole reported = scoped ? role.getParentRole() : role;
      String scopeValue = scoped && role.getForestClient() != null
          ? role.getForestClient().getForestClientNumber()
          : null;

      RoleAccumulator accumulator = byRoleName.computeIfAbsent(
          reported.getRoleName(),
          name -> new RoleAccumulator(
              role.getApplication().getApplicationName(),
              name,
              reported.getDisplayName(),
              scoped ? ScopeType.FOREST_CLIENT : null,
              new ArrayList<>()));

      if (scopeValue != null && !accumulator.values().contains(scopeValue)) {
        accumulator.values().add(scopeValue);
      }
    }

    return byRoleName.values().stream()
        .map(a -> new ExtRoleWithScopeDto(
            a.applicationName(), a.roleName(), a.roleDisplayName(), a.scopeType(), a.values()))
        .toList();
  }

  /**
   * FAM's stored code to the external vocabulary.
   *
   * <p>Anything that is not IDIR or Business BCeID is reported as BCSC - the
   * three BC Services Card codes (CD/CT/CP) are environment-specific and are not
   * exposed.
   */
  public static IdpType toIdpType(String userTypeCode) {
    if (UserType.IDIR.getCode().equals(userTypeCode)) {
      return IdpType.IDIR;
    }
    if (UserType.BCEID.getCode().equals(userTypeCode)) {
      return IdpType.BCEID;
    }
    return IdpType.BCSC;
  }

  private record RoleAccumulator(
      String applicationName,
      String roleName,
      String roleDisplayName,
      ScopeType scopeType,
      List<String> values) {}
}
