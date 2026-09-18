package ca.bc.gov.nrs.fam.configuration;

import java.util.List;
import java.util.UUID;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What the native image needs told that Spring's AOT processing does not work
 * out on its own.
 *
 * <p>Everything here is a reflective access that only happens at run time, so it
 * is invisible to the JVM build and to the tests - the failure is a native image
 * that compiles cleanly and then refuses to start. Each entry says which code
 * path needs it, because that is the only way to know later whether it is still
 * required.
 *
 * <p>Registered from {@link ca.bc.gov.nrs.fam.FamApiApplication} with
 * {@code @ImportRuntimeHints}.
 */
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * The id types of the entities, as arrays.
   *
   * <p>Hibernate builds a multi-id loader for every entity - see
   * {@code AbstractEntityPersister.buildMultiIdLoader} - and
   * {@code MultiIdEntityLoaderArrayParam} allocates an array of the entity's id
   * type reflectively while the SessionFactory is being built. GraalVM refuses
   * an unregistered allocation, so the application fails at start-up with
   * "Class java.util.UUID[] is instantiated reflectively but was never
   * registered", before it serves anything.
   *
   * <p>One entry per id type in {@code entity}: {@code UUID} for the audit and
   * terms-acceptance tables, {@code String} for the code table. An entity added
   * with an id of some other type needs its array adding here.
   */
  private static final List<Class<?>> ENTITY_ID_ARRAY_TYPES =
      List.of(UUID[].class, String[].class);

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> idArrayType : ENTITY_ID_ARRAY_TYPES) {
      hints.reflection().registerType(idArrayType, MemberCategory.UNSAFE_ALLOCATED);
    }
  }
}
