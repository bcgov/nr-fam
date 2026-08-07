package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamAccessControlPrivilegeRepository
    extends JpaRepository<FamAccessControlPrivilege, Long>,
        JpaSpecificationExecutor<FamAccessControlPrivilege> {

  Optional<FamAccessControlPrivilege> findByUserUserIdAndRoleRoleId(Long userId, Long roleId);

  List<FamAccessControlPrivilege> findByUserUserId(Long userId);

  /** Whether this user is a delegated admin of anything at all. */
  boolean existsByUserUserId(Long userId);

  /** The roles a delegated admin may manage within one application. */
  @Query("""
      select p.role.roleId from FamAccessControlPrivilege p
      where p.user.userId = :userId
        and p.role.application.applicationId = :applicationId
      """)
  List<Long> findManagedRoleIds(
      @Param("userId") Long userId, @Param("applicationId") Long applicationId);

  @Query("""
      select p from FamAccessControlPrivilege p
      where p.user.userId = :userId
        and p.role.application.applicationId = :applicationId
      """)
  List<FamAccessControlPrivilege> findByUserAndApplication(
      @Param("userId") Long userId, @Param("applicationId") Long applicationId);

  /**
   * Roles the user may grant to others as a delegated admin.
   *
   * <p>Port of {@code get_user_delegated_admin_grants}. Ordered by application
   * then role, because the caller groups the result on exactly that order.
   */
  @Query("""
      select r from FamAccessControlPrivilege p
      join p.role r
      join fetch r.application
      left join fetch r.parentRole
      left join fetch r.forestClient
      where p.user.userId = :userId
      order by r.application.applicationId, r.roleId
      """)
  List<FamRole> findDelegatedAdminGrantedRoles(@Param("userId") Long userId);

  /** Delegated-admin privileges within one application, for the admin listing. */
  @Query("""
      select p from FamAccessControlPrivilege p
      join fetch p.user u
      join fetch p.role r
      join fetch r.application
      left join fetch r.forestClient
      left join fetch u.userType
      where r.application.applicationId = :applicationId
      """)
  List<FamAccessControlPrivilege> findByApplicationId(
      @Param("applicationId") Long applicationId);
}
