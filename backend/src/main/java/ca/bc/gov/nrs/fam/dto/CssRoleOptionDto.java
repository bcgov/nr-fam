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
 * <p>{@code description} is always null: there is nowhere in CSS to put one.
 */
public record CssRoleOptionDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,

    /** The outermost, human-readable role. Same as {@link #name}. */
    String displayName,

    /** Always null - CSS has no field for it. Present so the shape is stable. */
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
