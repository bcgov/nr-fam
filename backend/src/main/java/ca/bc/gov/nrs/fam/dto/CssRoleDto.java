package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A role as CSS returns it: a name and a composite flag, and nothing else.
 *
 * <p>There is no display name, no description and no attributes. Everything FAM
 * needs beyond the name has to be encoded into the name itself or expressed
 * through composite membership - see {@link CssRoleNaming}.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CssRoleDto(String name, boolean composite) {}
