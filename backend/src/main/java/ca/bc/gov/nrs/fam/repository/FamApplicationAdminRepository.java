package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamApplicationAdmin;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamApplicationAdminRepository extends JpaRepository<FamApplicationAdmin, Long> {

  Optional<FamApplicationAdmin> findByUserUserIdAndApplicationApplicationId(
      Long userId, Long applicationId);

  List<FamApplicationAdmin> findByApplicationApplicationId(Long applicationId);

  List<FamApplicationAdmin> findByUserUserId(Long userId);

  boolean existsByUserUserIdAndApplicationApplicationId(Long userId, Long applicationId);

  /**
   * Applications a user administers, looked up by their identity rather than their
   * FAM user id.
   *
   * <p>Port of the app-admin query in
   * {@code auth_function.access_token_groups_override}. Each name becomes an
   * {@code <APPLICATION_NAME>_ADMIN} access role.
   */
  @Query("""
      select a.applicationName from FamApplicationAdmin adm
      join adm.user u
      join adm.application a
      where u.userGuid = :userGuid
        and u.userTypeCode = :userTypeCode
      """)
  List<String> findAdministeredApplicationNames(
      @Param("userGuid") String userGuid, @Param("userTypeCode") String userTypeCode);

  /**
   * Applications the user administers, including FAM itself.
   *
   * <p>Port of {@code ApplicationAdminRepository.get_user_app_admin_grants}. FAM
   * is deliberately included: its presence is what makes the user a FAM_ADMIN,
   * and the caller filters it out when building the APP_ADMIN grant.
   */
  @Query("""
      select adm.application from FamApplicationAdmin adm
      where adm.user.userId = :userId
      order by adm.application.applicationId
      """)
  List<FamApplication> findAdministeredApplications(@Param("userId") Long userId);

  @Query("""
      select adm from FamApplicationAdmin adm
      join fetch adm.user u
      join fetch adm.application
      left join fetch u.userType
      order by adm.applicationAdminId
      """)
  List<FamApplicationAdmin> findAllWithUserAndApplication();

  @Query("""
      select adm from FamApplicationAdmin adm
      join fetch adm.user u
      join fetch adm.application a
      left join fetch u.userType
      where a.applicationId = :applicationId
        and u.userId <> :excludeUserId
      order by adm.applicationAdminId
      """)
  List<FamApplicationAdmin> findByApplicationExcludingUser(
      @Param("applicationId") Long applicationId, @Param("excludeUserId") Long excludeUserId);
}
