package ca.bc.gov.nrs.fam.entity;

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
 * A privilege qualifier that can be assigned to a user within an application.
 *
 * <p>Roles are either abstract or concrete ({@link FamRoleType}). An abstract
 * role is a template; granting a user access "on behalf of" a forest client
 * creates a concrete child role with {@code parent_role_id} pointing at the
 * abstract role and {@code client_number_id} set.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_role",
    uniqueConstraints = @UniqueConstraint(name = "fam_rlnm_app_uk",
        columnNames = {"role_name", "application_id"}))
public class FamRole extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "role_id")
  private Long roleId;

  @Column(name = "role_name", nullable = false, length = 100)
  private String roleName;

  @Column(name = "role_purpose", length = 300)
  private String rolePurpose;

  /** Human-readable label shown in the UI; falls back to {@code role_name}. */
  @Column(name = "display_name", length = 100)
  private String displayName;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "application_id", nullable = false)
  private FamApplication application;

  /** Set only on concrete roles that are scoped to a forest client. */
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "client_number_id")
  private FamForestClient forestClient;

  /** The abstract role this concrete role was derived from. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_role_id")
  private FamRole parentRole;

  @OneToMany(mappedBy = "parentRole", fetch = FetchType.LAZY)
  private List<FamRole> childRoles = new ArrayList<>();

  /** Writable side of the code column; {@link #roleType} is the read-only join. */
  @Column(name = "role_type_code", nullable = false, length = 2)
  private String roleTypeCode;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "role_type_code", referencedColumnName = "role_type_code",
      insertable = false, updatable = false)
  private FamRoleType roleType;

  /**
   * Whether this role may call FAM's external API.
   *
   * <p>Nullable in the schema - V71 added the column as
   * {@code BOOLEAN DEFAULT FALSE} without NOT NULL, so pre-existing rows can
   * hold NULL even though the SQLAlchemy model declared it non-nullable. Read it
   * through {@link #isCallApiFlag()}, which treats NULL as false.
   */
  @Column(name = "call_api_flag")
  private Boolean callApiFlag;

  @OneToMany(mappedBy = "role", fetch = FetchType.LAZY)
  private List<FamUserRoleXref> userRoleXrefs = new ArrayList<>();

  @OneToMany(mappedBy = "role", fetch = FetchType.LAZY)
  private List<FamAccessControlPrivilege> accessControlPrivileges = new ArrayList<>();

  /** NULL-safe view of {@link #callApiFlag}; a missing value means "not permitted". */
  public boolean isCallApiFlag() {
    return Boolean.TRUE.equals(callApiFlag);
  }

  @Override
  public String toString() {
    return "FamRole(%d, %s, %s)".formatted(roleId, roleName, roleTypeCode);
  }
}
