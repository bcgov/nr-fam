package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One selectable role, sourced from CSS.
 *
 * <p>CSS roles cannot carry a display name, so meaning is expressed by nesting
 * them: a human-readable role composed of the machine role code, which may in
 * turn be composed of scope markers.
 *
 * <pre>
 * Submitter (CHR)  ->  CHR_FREP_EDITOR  ->  HAS_DISTRICT_ROLE
 * Submitter (SLR)  ->  FREP_EDITOR
 * Administrator    ->  FREP_ADMINISTRATOR
 * </pre>
 *
 * <p>{@code description} comes from the role's sidecar - see
 * {@link CssRoleNaming#LABEL_PREFIX} - and is null for a role that has none.
 */
public record CssRoleOptionDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,

    /**
     * The short human-readable name, from the role's {@code FAM:LABEL} sidecar.
     *
     * <p>e.g. {@code View All} for {@code FSPTS_VIEW_ALL}. What the pickers and
     * the permission pills show. Null for a role with no sidecar, which is why
     * every display falls back to {@link #name}.
     */
    String displayName,

    /**
     * The long description, from the role's {@code FAM:DESC} sidecar.
     *
     * <p>e.g. "Allows users to view all the FSPs but not edit". Null for a role
     * defined without one - including every role defined before descriptions
     * existed, which have a display name only.
     */
    String description,

    /** The machine role beneath the display role, or null if there is none. */
    String roleCode,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean composite,

    /** Every descendant in the composite chain, markers included. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> composites,

    /** Requires one or more districts before it can be granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean roleTypeDistrict,

    /** Requires one or more forest clients before it can be granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean roleTypeClient) {}
