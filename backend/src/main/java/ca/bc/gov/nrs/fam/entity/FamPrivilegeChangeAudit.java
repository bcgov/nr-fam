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
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only log of privilege grants, revocations and updates.
 *
 * <p>Does not extend {@link AuditedEntity}: the table has {@code create_user} and
 * {@code create_date} but no update columns, because rows are never modified.
 *
 * <p><b>Self-contained by design.</b> Roles, role assignments and applications
 * moved to CSS in V94, and this record deliberately kept no foreign keys into
 * what remained. An audit row that references mutable operational tables is only
 * as durable as those rows - removing a user would break history - so the
 * identifiers are recorded directly instead. The only relationship left is to the
 * change-type code table.
 *
 * <p>{@code change_date} is deliberately distinct from {@code create_date}. The
 * initial backfill wrote historical change dates onto rows created at migration
 * time, so the two differ for that data and {@code change_date} must never be
 * treated as a creation timestamp.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_privilege_change_audit")
public class FamPrivilegeChangeAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "privilege_change_audit_id")
  private Long privilegeChangeAuditId;

  /**
   * The CSS integration the change was made against.
   *
   * <p>Nullable only because rows written before V94 have no CSS equivalent - the
   * mapping from a FAM application to a CSS integration was not derivable at
   * migration time. Every row written since carries one.
   */
  @Column(name = "css_integration_id")
  private Integer cssIntegrationId;

  /**
   * The CSS environment, e.g. {@code dev}. A CSS integration spans environments,
   * so it takes both columns to identify what FAM calls an application.
   */
  @Column(name = "css_environment", length = 10)
  private String cssEnvironment;

  /** When the privilege change happened, which may predate this row. */
  @Column(name = "change_date", nullable = false)
  private OffsetDateTime changeDate;

  /**
   * Snapshot of who made the change, as JSON.
   *
   * <p>Stored as a snapshot rather than only an identifier so the audit trail
   * survives later edits to, or removal of, the performing user.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "change_performer_user_details", nullable = false)
  private String changePerformerUserDetails;

  /** Null when the change was made by a system process rather than a person. */
  @Column(name = "performer_user_guid", length = 32)
  private String performerUserGuid;

  @Column(name = "target_user_guid", length = 32)
  private String targetUserGuid;

  /** {@code I} or {@code B}; needed alongside the GUID to identify the user. */
  @Column(name = "target_user_type_code", length = 2)
  private String targetUserTypeCode;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "privilege_change_type_code", nullable = false)
  private FamPrivilegeChangeType privilegeChangeType;

  /** The privilege(s) changed, as JSON. Shape varies by permission type. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "privilege_details", nullable = false)
  private String privilegeDetails;

  @CreationTimestamp
  @Column(name = "create_date", nullable = false)
  private OffsetDateTime createDate;

  @Column(name = "create_user", nullable = false, length = 100)
  private String createUser;
}
