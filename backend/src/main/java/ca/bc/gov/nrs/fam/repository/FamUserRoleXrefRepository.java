package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamUserRoleXrefRepository
    extends JpaRepository<FamUserRoleXref, Long>, JpaSpecificationExecutor<FamUserRoleXref> {

  Optional<FamUserRoleXref> findByUserUserIdAndRoleRoleId(Long userId, Long roleId);

  List<FamUserRoleXref> findByUserUserId(Long userId);

  @Query("""
      select x from FamUserRoleXref x
      join fetch x.user
      join fetch x.role r
      left join fetch r.forestClient
      where r.application.applicationId = :applicationId
      """)
  List<FamUserRoleXref> findByApplicationId(@Param("applicationId") Long applicationId);

  /** Roles held by a user within one application, used by the external API. */
  @Query("""
      select x from FamUserRoleXref x
      join fetch x.role r
      left join fetch r.forestClient
      left join fetch r.parentRole
      where x.user.userId = :userId
        and r.application.applicationId = :applicationId
      """)
  List<FamUserRoleXref> findByUserAndApplication(
      @Param("userId") Long userId, @Param("applicationId") Long applicationId);
}
