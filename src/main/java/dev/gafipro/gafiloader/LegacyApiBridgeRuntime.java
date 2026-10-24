package dev.gafipro.gafiloader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Runtime implementation for the removed 1.21.11 pack metadata parsing helper.
 *
 * <p>Prefer the 26.2 replacement when present. The reflective fallback uses the
 * metadata type's codec and Gson's JSON parser, avoiding compile-time linkage to
 * game classes and preserving the original mod bytecode on disk.</p>
 */
public final class LegacyApiBridgeRuntime {
    private LegacyApiBridgeRuntime() {}

    public static Object getMetadataFromStream(Object metadataType, InputStream stream,
                                               Object packLocationInfo) {
        if (metadataType == null || stream == null) {
            return null;
        }

        ClassLoader loader = metadataType.getClass().getClassLoader();
        try {
            Object replacement = invokeTargetLoadMetadata(loader, metadataType, stream, packLocationInfo);
            if (replacement != NO_REPLACEMENT) {
                return replacement;
            }
            return parseWithMetadataCodec(loader, metadataType, stream);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Minecraft metadata parser failed", cause);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Legacy metadata bridge could not parse pack metadata: "
                    + exception.getMessage());
            return null;
        }
    }

    private static final Object NO_REPLACEMENT = new Object();

    private static Object invokeTargetLoadMetadata(ClassLoader loader, Object metadataType,
                                                   InputStream stream, Object location)
            throws ReflectiveOperationException {
        Class<?> owner;
        try {
            owner = Class.forName("net.minecraft.server.packs.AbstractPackResources", false, loader);
        } catch (ClassNotFoundException exception) {
            return NO_REPLACEMENT;
        }

        for (Method method : owner.getDeclaredMethods()) {
            if (!"loadMetadata".equals(method.getName())
                    || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if ((parameters.length != 2 && parameters.length != 3)
                    || !parameters[0].isInstance(metadataType)
                    || !parameters[1].isAssignableFrom(stream.getClass())) {
                continue;
            }
            if (parameters.length == 3
                    && location != null && !parameters[2].isInstance(location)) {
                continue;
            }
            method.setAccessible(true);
            return parameters.length == 2
                    ? method.invoke(null, metadataType, stream)
                    : method.invoke(null, metadataType, stream, location);
        }
        return NO_REPLACEMENT;
    }

    private static Object parseWithMetadataCodec(ClassLoader loader, Object metadataType,
                                                 InputStream stream)
            throws ReflectiveOperationException {
        Class<?> jsonParser = Class.forName("com.google.gson.JsonParser", true, loader);
        Method parseReader = jsonParser.getMethod("parseReader", Reader.class);
        Object json = parseReader.invoke(null,
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps", true, loader);
        Object ops = jsonOps.getField("INSTANCE").get(null);
        Object codec = metadataType.getClass().getMethod("codec").invoke(metadataType);

        Method codecParse = null;
        for (Method method : codec.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("parse".equals(method.getName()) && parameters.length == 2
                    && parameters[0].isInstance(ops) && parameters[1].isInstance(json)) {
                codecParse = method;
                break;
            }
        }
        if (codecParse == null) {
            throw new NoSuchMethodException("No compatible Codec.parse(DynamicOps, JsonElement)");
        }

        Object dataResult = codecParse.invoke(codec, ops, json);
        Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
        if (optional instanceof Optional<?> result) {
            return result.orElse(null);
        }
        return null;
    }
}
