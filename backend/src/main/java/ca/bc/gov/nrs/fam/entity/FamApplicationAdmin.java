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
 * Identifies who administers an application.
 *
 * <p>Introduced by V32 and populated from {@code fam_user_role_xref} by V34,
 * which moved admins out of the end-user role assignments.
 *
 * <p>Upstream this lived only in the admin-management API's model; it is part of
 * the single merged model here.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_application_admin",
    uniqueConstraints = @UniqueConstraint(name = "fam_app_admin_usr_app_uk",
        columnNames = {"user_id", "application_id"}))
public class FamApplicationAdmin extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "application_admin_id")
  private Long applicationAdminId;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private FamUser user;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "application_id", nullable = false)
  private FamApplication application;
}
