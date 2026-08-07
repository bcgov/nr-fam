package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamRoleRepository extends JpaRepository<FamRole, Long> {

  Optional<FamRole> findByRoleNameAndApplicationApplicationId(
      String roleName, Long applicationId);

  List<FamRole> findByApplicationApplicationId(Long applicationId);

  /**
   * Roles of an application excluding client-scoped child roles.
   *
   * <p>Port of {@code RoleRepository.get_base_roles_by_app_id}. A child role such
   * as {@code FOM_SUBMITTER_00001011} is an implementation detail of scoping and
   * must not be offered as something to grant.
   */
  @Query("""
      select r from FamRole r
      where r.application.applicationId = :applicationId
        and r.parentRole is null
      order by r.roleId
      """)
  List<FamRole> findBaseRolesByApplicationId(@Param("applicationId") Long applicationId);

  /**
   * Role names a user holds within the application that owns an OIDC client.
   *
   * <p>Port of the roles query in {@code auth_function.access_token_groups_override},
   * which Cognito ran at token-generation time to populate {@code cognito:groups}.
   * Keycloak cannot do that without a custom SPI, so FAM resolves the same set on
   * demand instead.
   *
   * <p>Expired assignments are excluded here rather than filtered afterwards - an
   * expired role must never appear in a caller's effective permissions.
   */
  @Query("""
      select r.roleName from FamUserRoleXref x
      join x.user u
      join x.role r
      join r.application a
      join a.applicationClients c
      where u.userGuid = :userGuid
        and u.userTypeCode = :userTypeCode
        and c.oidcClientId = :oidcClientId
        and (x.expiryDate is null or x.expiryDate >= current_timestamp)
      """)
  List<String> findRoleNamesForUserAndClient(
      @Param("userGuid") String userGuid,
      @Param("userTypeCode") String userTypeCode,
      @Param("oidcClientId") String oidcClientId);

  /**
   * Whether the named user holds a role permitting external API calls for this
   * application.
   *
   * <p>Port of {@code crud_utils.allow_ext_call_api_permission}. A concrete role
   * scoped to a forest client inherits the flag from its parent, so both the role
   * and its parent are considered.
   */
  @Query("""
      select case when count(x) > 0 then true else false end
      from FamUserRoleXref x
      join x.user u
      join x.role r
      left join r.parentRole p
      where u.userName = :userName
        and r.application.applicationId = :applicationId
        and (r.callApiFlag = true or p.callApiFlag = true)
      """)
  boolean hasExternalApiPermission(
      @Param("applicationId") Long applicationId, @Param("userName") String userName);
}
