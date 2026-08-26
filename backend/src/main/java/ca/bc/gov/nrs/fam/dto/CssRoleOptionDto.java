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

    /** Requires one or more regions before it can be granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean roleTypeRegion,

    /** Requires one or more forest clients before it can be granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean roleTypeClient,

    /**
     * The districts this caller may grant the role for, or null when they are
     * not restricted to any.
     *
     * <p><b>Null and empty mean different things, and the difference is the
     * point.</b> Null is "no restriction" - a FAM or application administrator,
     * who may grant every district. Empty is "restricted to nothing", which is
     * what a delegated administrator holds when their delegation names no
     * district for a role that requires one: they see the role and can choose
     * nothing, which is the truth.
     *
     * <p>A delegation is a concrete role name with the scope in it -
     * {@code FREP_EDITOR_DISTRICT-DCC} - so this is those values, gathered
     * across every delegation the caller holds for this role.
     *
     * <p>Presentation only. {@code requireGrantableRoles} refuses a grant
     * outside the delegation whatever the picker offered; this is what stops the
     * screen offering it in the first place.
     */
    List<String> grantableDistricts,

    /**
     * The regions this caller may grant the role for, or null when unrestricted.
     *
     * <p>Same null/empty distinction as {@link #grantableDistricts}: null is a
     * FAM or application administrator who may grant every region, empty is a
     * delegated administrator whose delegation names none.
     */
    List<String> grantableRegions,

    /**
     * The organisations this caller may grant for. Null when unrestricted.
     *
     * <p>Resolved rather than left as numbers, because the picker for a
     * restricted caller is a list rather than a search box - a delegation names
     * a handful of organisations, and there is nothing to search for. A number
     * with no name beside it is not something anybody can pick from.
     *
     * <p>Only the active ones. An inactive organisation is refused on selection
     * anyway, so offering it would be offering a dead end - the same rule the
     * search applies, and the same one that hides an expired district.
     */
    List<FamForestClientDto> grantableForestClients) {}
