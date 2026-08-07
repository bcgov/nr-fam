package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamUserRepository
    extends JpaRepository<FamUser, Long>, JpaSpecificationExecutor<FamUser> {

  /**
   * The natural key since V50: identity provider plus GUID.
   *
   * <p>{@code user_name} alone is not unique - the same name can exist under
   * different providers.
   */
  Optional<FamUser> findByUserTypeCodeAndUserGuidIgnoreCase(String userTypeCode, String userGuid);

  Optional<FamUser> findByOidcUserId(String oidcUserId);

  Optional<FamUser> findByUserTypeCodeAndUserNameIgnoreCase(String userTypeCode, String userName);

  /** Users whose details are refreshed from IDIM. BCSC users are not looked up there. */
  @Query("""
      select u from FamUser u
      where u.userTypeCode in :userTypeCodes
      order by u.userId
      """)
  Page<FamUser> findByUserTypeCodeIn(
      @Param("userTypeCodes") List<String> userTypeCodes, Pageable pageable);

  @Query("""
      select u from FamUser u
      where u.userTypeCode in :userTypeCodes
      order by u.userId
      """)
  List<FamUser> findAllByUserTypeCodeIn(@Param("userTypeCodes") List<String> userTypeCodes);
}
