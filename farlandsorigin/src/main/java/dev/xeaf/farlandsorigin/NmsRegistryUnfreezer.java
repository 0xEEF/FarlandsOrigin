package dev.xeaf.farlandsorigin;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * On Fabric, {@code Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ...)}
 * works because Fabric's mod-init hook runs before {@code Bootstrap.bootStrap()}
 * freezes the built-in registries. Paper plugins never get to run that early -
 * by the time {@link FarlandsOriginBootstrap#bootstrap} executes,
 * {@code BuiltInRegistries.DENSITY_FUNCTION_TYPE} is already frozen.
 *
 * There is no supported Paper API for this (its {@code RegistryEvents} only
 * covers a fixed set of data-driven, datapack-backed registries - enchantments,
 * entity variants, dialogs, etc. - not the Java-side codec-dispatch registries
 * worldgen density functions use). So this reflects into vanilla's
 * {@code MappedRegistry} to temporarily unfreeze it, exactly the way several
 * existing NMS-based worldgen plugins already do for similar problems.
 *
 * This is inherently fragile across Minecraft versions: if Mojang renames the
 * frozen-state field, this will fail loudly (NoSuchFieldException) at server
 * boot rather than silently generating wrong terrain, which is the safest
 * failure mode available here. Verify the field name below against the
 * actual 26.1/26.2 {@code MappedRegistry} class if this throws.
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
