package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps an OIDC client id to an application, so FAM can tell which application a
 * token was issued for.
 *
 * <p>Many-to-one: an application may have several OIDC clients (for example a
 * public and a confidential client) while its authorization is configured once.
 *
 * <p>The column is named {@code cognito_client_id} for historical reasons. FAM
 * no longer uses AWS Cognito - these are BC Gov SSO (Keycloak) client ids. The
 * column has not been renamed because the seed data in 50+ migrations references
 * it; see migrations/README.md.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_application_client",
    uniqueConstraints = @UniqueConstraint(name = "cognito_app_uk",
        columnNames = {"cognito_client_id", "application_id"}))
public class FamApplicationClient extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "application_client_id")
  private Long applicationClientId;

  /** The OIDC client id. Named for Cognito; now a Keycloak client id. */
  @Column(name = "cognito_client_id", nullable = false, length = 32)
  private String oidcClientId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "application_id")
  private FamApplication application;
}
