package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamApplication;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamApplicationRepository extends JpaRepository<FamApplication, Long> {

  Optional<FamApplication> findByApplicationName(String applicationName);

  /**
   * Resolve the application a token was issued for, from its OIDC client id.
   *
   * <p>Port of {@code crud_application.get_application_by_app_client_id}. The
   * column is still named {@code cognito_client_id}; it now holds a Keycloak
   * client id.
   */
  @Query("""
      select a from FamApplication a
      join a.applicationClients c
      where c.oidcClientId = :oidcClientId
      """)
  Optional<FamApplication> findByOidcClientId(@Param("oidcClientId") String oidcClientId);
}
