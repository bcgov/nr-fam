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

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "application_id", nullable = false)
  private FamApplication application;

  /** When the privilege change happened, which may predate this row. */
  @Column(name = "change_date", nullable = false)
  private OffsetDateTime changeDate;

  /**
   * Snapshot of who made the change, as JSON.
   *
   * <p>Stored as a snapshot rather than only a foreign key so the audit trail
   * survives later edits to, or removal of, the performing user.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "change_performer_user_details", nullable = false)
  private String changePerformerUserDetails;

  /** Null when the change was made by a system process rather than a person. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "change_performer_user_id")
  private FamUser changePerformerUser;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "change_target_user_id", nullable = false)
  private FamUser changeTargetUser;

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
