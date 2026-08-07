package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to the privilege change audit trail.
 *
 * <p>Port of {@code crud_permission_audit.py}. The two JSONB columns are stored
 * as raw JSON on the entity and deserialised here, keeping Hibernate out of the
 * business of understanding their shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditService {

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final ObjectMapper objectMapper;

  /** Most recent change first. */
  @Transactional(readOnly = true)
  public List<PermissionAuditHistoryDto> getHistory(Long userId, Long applicationId) {
    return auditRepository.findHistory(userId, applicationId).stream()
        .map(this::toDto)
        .toList();
  }

  private PermissionAuditHistoryDto toDto(FamPrivilegeChangeAudit audit) {
    return new PermissionAuditHistoryDto(
        audit.getPrivilegeChangeAuditId(),
        audit.getCreateDate(),
        audit.getCreateUser(),
        audit.getChangeDate(),
        readJson(audit.getChangePerformerUserDetails(), PrivilegeChangePerformerDto.class,
            audit.getPrivilegeChangeAuditId(), "change_performer_user_details"),
        audit.getChangePerformerUser() != null
            ? audit.getChangePerformerUser().getUserId()
            : null,
        PrivilegeChangeType.valueOf(
            audit.getPrivilegeChangeType().getPrivilegeChangeTypeCode()),
        audit.getPrivilegeChangeType().getDescription(),
        readJson(audit.getPrivilegeDetails(), PrivilegeDetailsDto.class,
            audit.getPrivilegeChangeAuditId(), "privilege_details"));
  }

  /**
   * Audit rows are append-only and written by FAM itself, so unreadable JSON means
   * corrupted data rather than bad input - surfaced as a 500 rather than silently
   * returning a partial record.
   */
  private <T> T readJson(String json, Class<T> type, Long auditId, String column) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      log.error("Cannot parse {} of audit record {}", column, auditId, e);
      throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
          "Audit record " + auditId + " has unreadable " + column + ".");
    }
  }
}
