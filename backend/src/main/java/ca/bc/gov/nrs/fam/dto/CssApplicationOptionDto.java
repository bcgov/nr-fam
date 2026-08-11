package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One selectable application, derived from a CSS integration by fanning out its
 * {@code environments} array.
 *
 * <p>A CSS integration spans environments, where a FAM application has always
 * been per-environment. So an integration named "Forest and Range Evaluation
 * Program" with environments {@code [dev, test]} yields two options here.
 *
 * <p>The pair {@code (integrationId, environment)} is the identifier - there is
 * no single id, which is why callers pass both on every downstream call.
 */
public record CssApplicationOptionDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer integrationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String environment,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
    String status) {}
