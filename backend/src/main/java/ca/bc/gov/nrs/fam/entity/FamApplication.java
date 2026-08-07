package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A digital product whose access FAM controls.
 *
 * <p>One row per application <em>per environment</em> - FOM_DEV, FOM_TEST and
 * FOM_PROD are three rows, distinguished by {@code app_environment}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_application",
    uniqueConstraints = @UniqueConstraint(name = "fam_app_name_uk",
        columnNames = "application_name"))
public class FamApplication extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "application_id")
  private Long applicationId;

  @Column(name = "application_name", nullable = false, length = 100)
  private String applicationName;

  @Column(name = "application_description", nullable = false, length = 200)
  private String applicationDescription;

  /**
   * DEV, TEST or PROD. Nullable: FAM's own application row and some legacy rows
   * have no environment, which is why the self-grant rule is an allowlist rather
   * than a "not PROD" test.
   */
  @Column(name = "app_environment", length = 4)
  private String appEnvironment;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "app_environment", referencedColumnName = "app_environment",
      insertable = false, updatable = false)
  private FamAppEnvironment appEnvironmentRelation;

  @OneToMany(mappedBy = "application", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
  private List<FamApplicationClient> applicationClients = new ArrayList<>();

  @OneToMany(mappedBy = "application", fetch = FetchType.LAZY)
  private List<FamRole> roles = new ArrayList<>();

  @Override
  public String toString() {
    return "FamApplication(%d, %s, %s)".formatted(applicationId, applicationName, appEnvironment);
  }
}
