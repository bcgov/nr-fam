package ca.bc.gov.nrs.fam.integration;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for what {@link ClientCredentialsTokenSource} parses.
 *
 * <p>A registrar rather than {@code @RegisterReflectionForBinding} on the class
 * itself, which is what {@link CssApiService} uses. That annotation is only read
 * from beans, and this token source is not one - it is constructed directly by
 * whoever needs a token - so the annotation was silently ignored and the records
 * never made it into the image. Nothing failed at build time; the first sign was
 * a token exchange returning nothing at run time.
 *
 * <p>In this package because the records it names are package-private, and they
 * are package-private because nothing outside needs them.
 *
 * <p>Registered from {@link ca.bc.gov.nrs.fam.FamApiApplication}.
 */
public class TokenSourceRuntimeHints implements RuntimeHintsRegistrar {

  private final BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    binding.registerReflectionHints(
        hints.reflection(),
        ClientCredentialsTokenSource.TokenResponse.class,
        ClientCredentialsTokenSource.TokenErrorResponse.class);
  }
}
