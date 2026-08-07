package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of {@code crud_role.py}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

  private final FamRoleRepository roleRepository;
  private final ForestClientService forestClientService;

  @Transactional(readOnly = true)
  public FamRole getRole(Long roleId) {
    return roleRepository.findById(roleId).orElse(null);
  }

  @Transactional(readOnly = true)
  public Optional<FamRole> findByNameAndApplication(String roleName, Long applicationId) {
    return roleRepository.findByRoleNameAndApplicationApplicationId(roleName, applicationId);
  }

  /** {@code FOM_REVIEWER} scoped to client 00001011 becomes {@code FOM_REVIEWER_00001011}. */
  public static String constructForestClientRoleName(
      String parentRoleName, String forestClientNumber) {
    return parentRoleName + "_" + forestClientNumber;
  }

  public static String constructForestClientRolePurpose(
      String parentRolePurpose, String forestClientNumber) {
    return parentRolePurpose + " for " + forestClientNumber;
  }

  /**
   * Get - or create - the concrete role that pairs an abstract role with one
   * forest client.
   *
   * <p>Port of {@code find_or_create_forest_client_child_role}. FAM does not store
   * a scope on an assignment; instead it materialises a child role per
   * (abstract role, client) pair and assigns users to that. The child inherits the
   * parent's display name and {@code call_api_flag}, so a scoped grant carries the
   * same API permission as the unscoped parent.
   */
  @Transactional
  public FamRole findOrCreateForestClientChildRole(
      String forestClientNumber, FamRole parentRole, String requesterOidcId) {

    FamForestClient forestClient =
        forestClientService.findOrCreate(forestClientNumber, requesterOidcId);

    String childRoleName =
        constructForestClientRoleName(parentRole.getRoleName(), forestClientNumber);
    Long applicationId = parentRole.getApplication().getApplicationId();

    Optional<FamRole> existing = findByNameAndApplication(childRoleName, applicationId);
    if (existing.isPresent()) {
      log.debug("Forest client child role '{}' already exists", childRoleName);
      return existing.get();
    }

    FamRole childRole = new FamRole();
    childRole.setRoleName(childRoleName);
    childRole.setDisplayName(parentRole.getDisplayName());
    childRole.setRolePurpose(constructForestClientRolePurpose(
        parentRole.getRolePurpose(), forestClientNumber));
    childRole.setApplication(parentRole.getApplication());
    childRole.setParentRole(parentRole);
    childRole.setForestClient(forestClient);
    childRole.setRoleTypeCode(RoleType.CONCRETE.getCode());
    // Inherited so a client-scoped grant has the same external API rights as the
    // parent role it derives from.
    childRole.setCallApiFlag(parentRole.isCallApiFlag());
    childRole.setCreateUser(requesterOidcId);

    FamRole saved = roleRepository.save(childRole);
    log.debug("Child role {} created for parent role {}", saved.getRoleId(),
        parentRole.getRoleName());
    return saved;
  }
}
