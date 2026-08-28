package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One person who has audit history in an application.
 *
 * <p>What the history screen offers once an application is chosen. Read from the
 * trail rather than from CSS: this is the list of people something has
 * <em>happened to</em> here, which includes those whose access was since removed
 * and excludes those who were granted theirs before FAM recorded anything.
 *
 * <p>The name and username are the snapshot the trail took at the time - see
 * {@code change_target_user_details}. A person renamed or removed since still
 * reads as they did then, which is the point of recording it.
 */
public record PermissionAuditUserDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetUserGuid,

    /**
     * Which directory they came from.
     *
     * <p>Carried because the history call needs it: the trail keys on
     * {@code <TYPE>\<GUID>}, so the GUID alone would match nothing.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserType targetUserType,

    String username,
    String firstName,
    String lastName,
    String email,

    /** Their most recent change here, which is what the list is ordered by. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastChangeDate) {}
