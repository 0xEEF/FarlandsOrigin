package dev.xeaf.farlandsorigin;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Registers into a Java built-in registry that Paper has no API for (its
 * {@code RegistryEvents} only covers datapack-backed registries such as
 * enchantments, entity variants and dialogs - not the codec-dispatch
 * registries worldgen density functions use).
 *
 * Timing is what makes this work: Paper runs plugin bootstrappers at the top
 * of {@code Bootstrap.bootStrap()}, BEFORE {@code BuiltInRegistries.bootStrap()}
 * fills the built-in registries and freezes them. At that moment
 * {@code DENSITY_FUNCTION_TYPE} already exists (referencing it initializes
 * {@code BuiltInRegistries}) but is still empty and unfrozen, so a plain
 * {@code Registry.register} is enough, and the later {@code freeze()} binds
 * our entry's holder along with all the vanilla ones. (An earlier version of
 * this comment claimed the registry was already frozen; it isn't.)
 *
 * The unfreeze/refreeze below is therefore only a safety net, for a
 * hypothetical future where bootstrappers run later. Note it would NOT be a
 * complete fix there: {@code register} leaves the new holder unbound until
 * the next {@code freeze()}, so values registered into an already-frozen
 * registry can't be read. If that ever becomes the situation, bind the
 * holder too, or run earlier.
 *
 * Fragile across Minecraft versions in the way reflection always is: if
 * Mojang renames the frozen-state field of {@code MappedRegistry} this fails
 * loudly at boot, rather than silently generating wrong terrain.
 */
final class NmsRegistryUnfreezer {
    private NmsRegistryUnfreezer() {}

    /** Field name in net.minecraft.core.MappedRegistry as of recent versions. Verify per-version. */
    private static final String FROZEN_FIELD = "frozen";

    /**
     * Deliberately typed loosely (raw {@link Registry}/{@link MapCodec}) rather
     * than trying to make the generics line up: the caller passes
     * {@code BuiltInRegistries.DENSITY_FUNCTION_TYPE}, typed as
     * {@code Registry<MapCodec<? extends DensityFunction>>}, together with a
     * {@code MapCodec<RadiusCheck>} - Java's invariant generics can't unify
     * "T is DensityFunction" and "T is RadiusCheck" through one type
     * parameter, even though RadiusCheck implements DensityFunction and this
     * is exactly what {@code Registry.register} allows at the bytecode level.
     * Since this whole method already sidesteps the type system via
     * reflection to unfreeze the registry, dropping to raw types for the
     * actual register call is consistent with that, not a new kind of unsafe.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void forceRegister(Registry registry, Identifier id, MapCodec<? extends DensityFunction> codec) {
        try {
            Field frozenField = registry.getClass().getDeclaredField(FROZEN_FIELD);
            frozenField.setAccessible(true);

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(registry.getClass(), MethodHandles.lookup());
            MethodHandle frozenSetter = lookup.unreflectSetter(frozenField);

            boolean wasFrozen = frozenField.getBoolean(registry);
            if (wasFrozen) {
                frozenSetter.invoke(registry, false);
            }

            try {
                Registry.register(registry, id, codec);
            } finally {
                if (wasFrozen) {
                    frozenSetter.invoke(registry, true);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not reflectively register " + id + " into " + registry +
                    ". This usually means Mojang changed MappedRegistry's internal field names " +
                    "in this Minecraft version - update NmsRegistryUnfreezer.FROZEN_FIELD.", e);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to force-register " + id + " into " + registry, t);
        }
    }
}
