package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModCompatibilityTransformerTest {
    private static final String SYSTEM_REPORT_INTERMEDIARY_OWNER = "net/minecraft/class_9001";
    private static final String SYSTEM_REPORT_MIXIN_CLASS =
            "net/fabricmc/fabric/mixin/crash/report/info/SystemReportMixin";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_ANNOTATION = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String LEGACY_SYSTEM_REPORT_DETAIL_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/util/function/Supplier;)V";
    private static final String TARGET_SYSTEM_REPORT_DETAIL_DESCRIPTOR =
            "(Ljava/lang/String;Lnet/minecraft/CrashReportDetail;)V";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rewritesEntityRegistryReferenceInsideNestedFabricApiJarWithoutChangingOriginals() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] legacyMixin = classWithIntermediaryEntityTypePlayerReference();
        byte[] nestedRenderingJar = jar(Map.of(
                "net/fabricmc/fabric/mixin/client/rendering/EntityRenderersMixin.class", legacyMixin,
                "META-INF/TEST.SF", "old-signature".getBytes(StandardCharsets.UTF_8)));
        byte[] fabricApiJar = jar(Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fabric-api\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-rendering-v1.jar", nestedRenderingJar));
        byte[] otherModJar = jar(Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"other-mod\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "example/OtherMod.class", classWithNoFieldReferences()));

        Path fabricApi = modsDirectory.resolve("fabric-api.jar");
        Path otherMod = modsDirectory.resolve("other-mod.jar");
        Files.write(fabricApi, fabricApiJar);
        Files.write(otherMod, otherModJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenReferences());
        assertEquals(1, result.modifiedJars());
        assertEquals(gameDirectory.resolve(".gafiloader/compat-mods").toAbsolutePath().normalize(),
                result.modsDirectory());
        assertArrayEquals(fabricApiJar, Files.readAllBytes(fabricApi),
                "The original Fabric API outer JAR must remain byte-for-byte unchanged.");
        assertArrayEquals(otherModJar, Files.readAllBytes(otherMod),
                "Other original mod JARs must remain byte-for-byte unchanged.");

        Path stagedApi = result.modsDirectory().resolve("fabric-api.jar");
        Path stagedOther = result.modsDirectory().resolve("other-mod.jar");
        assertArrayEquals(otherModJar, Files.readAllBytes(stagedOther),
                "Unmodified mods should be copied without rewriting their content.");

        byte[] stagedNestedJar = readEntry(Files.readAllBytes(stagedApi),
                "META-INF/jars/fabric-rendering-v1.jar");
        byte[] stagedMixin = readEntry(stagedNestedJar,
                "net/fabricmc/fabric/mixin/client/rendering/EntityRenderersMixin.class");
        assertEquals("net/minecraft/world/entity/EntityTypes", firstFieldOwner(stagedMixin));
        assertEquals("PLAYER", firstFieldName(stagedMixin));
        assertEquals("Lnet/minecraft/class_1299;", firstFieldDescriptor(stagedMixin),
                "Keep the legacy descriptor for Fabric Loader to remap; only the new owner/name are injected.");
        assertFalse(hasEntry(stagedNestedJar, "META-INF/TEST.SF"),
                "Signatures on a modified nested archive must be removed.");
    }

    @Test
    void remapsLegacyFabricApiBytecodeInNestedJarBeforeFabricLoadsIt() throws Exception {
        LegacyClassTweakerMappings mappings = createBytecodeMappings();
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-bytecode-remap"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        byte[] originalClass = classWithIntermediaryRegistryReferences();
        byte[] nestedModule = jar(Map.of("net/example/LegacyApiEntrypoint.class", originalClass));
        byte[] originalApi = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11",
                         "depends":{"minecraft":">=1.21.11"}}
                        """.getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-registry-api-v1.jar", nestedModule));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalApi);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.remappedClasses());
        assertEquals(0, result.rewrittenReferences());
        assertEquals(1, result.modifiedJars());
        assertArrayEquals(originalApi, Files.readAllBytes(source),
                "The source Fabric API JAR must remain byte-for-byte unchanged.");

        byte[] stagedNested = readEntry(Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar")),
                "META-INF/jars/fabric-registry-api-v1.jar");
        byte[] stagedClass = readEntry(stagedNested, "net/example/LegacyApiEntrypoint.class");
        AtomicReference<String> registryFieldDescriptor = new AtomicReference<>();
        AtomicReference<String> lookupMethodDescriptor = new AtomicReference<>();
        AtomicReference<String> invocationOwner = new AtomicReference<>();
        AtomicReference<String> invocationName = new AtomicReference<>();
        AtomicReference<String> invocationDescriptor = new AtomicReference<>();
        AtomicReference<String> staticFieldOwner = new AtomicReference<>();
        AtomicReference<String> staticFieldName = new AtomicReference<>();
        AtomicReference<String> staticFieldDescriptor = new AtomicReference<>();

        new ClassReader(stagedClass).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if ("registry".equals(name)) registryFieldDescriptor.set(descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("lookup".equals(name)) lookupMethodDescriptor.set(descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (invocationOwner.get() == null && owner.contains("Registry")) {
                            invocationOwner.set(owner);
                            invocationName.set(methodName);
                            invocationDescriptor.set(methodDescriptor);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        if (staticFieldOwner.get() == null) {
                            staticFieldOwner.set(owner);
                            staticFieldName.set(fieldName);
                            staticFieldDescriptor.set(fieldDescriptor);
                        }
                    }
                };
            }
        }, 0);

        String registryDescriptor = "Lnet/minecraft/core/Registry;";
        String lookupDescriptor =
                "(Lnet/minecraft/core/Registry;)Lnet/minecraft/core/Registry;";
        assertEquals(registryDescriptor, registryFieldDescriptor.get());
        assertEquals(lookupDescriptor, lookupMethodDescriptor.get());
        assertEquals("net/minecraft/core/Registry", invocationOwner.get());
        assertEquals("lookup", invocationName.get());
        assertEquals(lookupDescriptor, invocationDescriptor.get());
        assertEquals("net/minecraft/core/Registry", staticFieldOwner.get());
        assertEquals("entry", staticFieldName.get());
        assertEquals(registryDescriptor, staticFieldDescriptor.get());
    }

    @Test
    void adaptsLegacyScreenApiMixinInsideNestedFabricApiModuleWithoutChangingOriginals() throws Exception {
        LegacyClassTweakerMappings mappings = createBytecodeMappings();
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-screen-api"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        byte[] originalMixin = classWithLegacyScreenApiMixin();
        byte[] originalScreenModule = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-screen-api-v1","version":"1.0.0+1.21.11",
                         "depends":{"minecraft":">=1.21.11"}}
                        """.getBytes(StandardCharsets.UTF_8),
                "fabric-screen-api-v1.mixins.json", """
                        {"required":true,"package":"net.fabricmc.fabric.mixin.screen",
                         "client":["GameRendererMixin","MinecraftMixin","ScreenMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "net/fabricmc/fabric/mixin/screen/MinecraftMixin.class", originalMixin));
        byte[] originalApi = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11",
                         "depends":{"minecraft":">=1.21.11"}}
                        """.getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-screen-api-v1.jar", originalScreenModule));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalApi);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.adaptedScreenApiMixins());
        assertEquals(1, result.remappedClasses());
        assertArrayEquals(originalApi, Files.readAllBytes(source),
                "The original Fabric API outer JAR must remain byte-for-byte unchanged.");
        assertArrayEquals(originalScreenModule, readEntry(originalApi,
                "META-INF/jars/fabric-screen-api-v1.jar"),
                "The original nested Screen API module must remain byte-for-byte unchanged.");

        byte[] stagedModule = readEntry(Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar")),
                "META-INF/jars/fabric-screen-api-v1.jar");
        assertTrue(hasEntry(stagedModule, LegacyFabricScreenApiAdapter.GENERATED_MIXIN_ENTRY),
                "The generated GuiManager mixin must be placed in the staged nested module.");

        byte[] stagedConfig = readEntry(stagedModule, LegacyFabricScreenApiAdapter.MIXIN_CONFIG);
        com.google.gson.JsonArray clientMixins = com.google.gson.JsonParser.parseString(
                new String(stagedConfig, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("client");
        assertTrue(clientMixins.asList().stream().anyMatch(value ->
                "GafiLoaderGuiManagerMixin".equals(value.getAsString())));

        byte[] stagedMinecraftMixin = readEntry(stagedModule,
                "net/fabricmc/fabric/mixin/screen/MinecraftMixin.class");
        ClassReader minecraftMixinReader = new ClassReader(stagedMinecraftMixin);
        assertEquals("net/fabricmc/fabric/mixin/screen/MinecraftMixin",
                minecraftMixinReader.getClassName());
        assertEquals("net/minecraft/client/MinecraftClient", LegacyBytecodeRemapper.mixinTargets(stagedMinecraftMixin)
                .get(0));
        byte[] stagedGuiManagerMixin = readEntry(stagedModule,
                LegacyFabricScreenApiAdapter.GENERATED_MIXIN_ENTRY);
        assertEquals(Set.of("setScreen"),
                injectMethodTargetsForMethod(stagedGuiManagerMixin, "onScreenRemove"));
        assertEquals(Set.of("tick"),
                injectMethodTargetsForMethod(stagedGuiManagerMixin, "beforeScreenTick"));
        assertEquals(Set.of("tick"),
                injectMethodTargetsForMethod(stagedGuiManagerMixin, "afterScreenTick"));
        assertEquals(Set.of("doWorldLoad"),
                injectMethodTargetsForMethod(stagedMinecraftMixin, "beforeLoadingScreenTick"));
        assertEquals(Set.of("doWorldLoad"),
                injectMethodTargetsForMethod(stagedMinecraftMixin, "afterLoadingScreenTick"));
        assertEquals("Lnet/minecraft/client/gui/screen/Screen;removed()V",
                atTargetForMethod(stagedGuiManagerMixin, "onScreenRemove"),
                "The @At selector must use the singular 26.2 Screen package.");
        assertEquals("Lnet/minecraft/client/gui/screen/Screen;tick()V",
                atTargetForMethod(stagedGuiManagerMixin, "beforeScreenTick"));
        assertEquals("Lnet/minecraft/client/gui/screen/Screen;tick()V",
                atTargetForMethod(stagedGuiManagerMixin, "afterScreenTick"));
        assertEquals("Lnet/minecraft/client/gui/screens/LevelLoadingScreen;tick()V",
                atTargetForMethod(stagedMinecraftMixin, "beforeLoadingScreenTick"),
                "LevelLoadingScreen retains its plural package in 26.2.");
        assertEquals("Lnet/minecraft/client/MinecraftClient;runTick(Z)V",
                atTargetForMethod(stagedMinecraftMixin, "afterLoadingScreenTick"),
                "The old Minecraft runTick selector must target MinecraftClient in 26.2.");
    }

    @Test
    void adaptsSystemReportMixinInsideNestedFabricApiModuleWithoutChangingOriginals() throws Exception {
        LegacyClassTweakerMappings mappings = createBytecodeMappings();
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-system-report"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        byte[] originalMixin = classWithLegacySystemReportMixin();
        byte[] originalCrashModule = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-crash-report-info-v1","version":"1.0.0+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                SYSTEM_REPORT_MIXIN_CLASS + ".class", originalMixin));
        byte[] originalApi = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-crash-report-info-v1.jar", originalCrashModule));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalApi);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.remappedClasses());
        assertEquals(1, result.modifiedJars());
        assertArrayEquals(originalApi, Files.readAllBytes(source),
                "The original Fabric API outer JAR must remain byte-for-byte unchanged.");

        byte[] stagedCrashModule = readEntry(
                Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar")),
                "META-INF/jars/fabric-crash-report-info-v1.jar");
        assertArrayEquals(originalCrashModule,
                readEntry(originalApi, "META-INF/jars/fabric-crash-report-info-v1.jar"),
                "The original outer JAR must contain the exact original nested module bytes.");
        byte[] stagedMixin = readEntry(stagedCrashModule, SYSTEM_REPORT_MIXIN_CLASS + ".class");

        AtomicReference<String> mappedTarget = new AtomicReference<>();
        AtomicReference<String> shadowDescriptor = new AtomicReference<>();
        AtomicReference<String> callSiteName = new AtomicReference<>();
        AtomicReference<String> callSiteDescriptor = new AtomicReference<>();
        AtomicReference<String> invocationName = new AtomicReference<>();
        AtomicReference<String> invocationDescriptor = new AtomicReference<>();

        new ClassReader(stagedMixin).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type type) {
                            mappedTarget.set(type.getInternalName());
                        }
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("setDetail".equals(name)) shadowDescriptor.set(descriptor);
                if (!"fillSystemDetails".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor,
                            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                        callSiteName.set(indyName);
                        callSiteDescriptor.set(indyDescriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if ("setDetail".equals(methodName)) {
                            invocationName.set(methodName);
                            invocationDescriptor.set(methodDescriptor);
                        }
                    }
                };
            }
        }, 0);

        assertEquals("net/minecraft/SystemReport", mappedTarget.get());
        assertEquals(TARGET_SYSTEM_REPORT_DETAIL_DESCRIPTOR, shadowDescriptor.get());
        assertEquals("call", callSiteName.get(),
                "CrashReportDetail inherits Callable.call(), not Supplier.get().");
        assertEquals("()Lnet/minecraft/CrashReportDetail;", callSiteDescriptor.get());
        assertEquals("setDetail", invocationName.get());
        assertEquals(TARGET_SYSTEM_REPORT_DETAIL_DESCRIPTOR, invocationDescriptor.get());
    }

    @Test
    void stagesTargetAwareRemappedRefmapWithoutChangingTheOriginalModJar() throws Exception {
        LegacyClassTweakerMappings mappings = createBytecodeMappings();
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-refmap"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        String mixinClass = "net/fabricmc/fabric/mixin/registry/LegacyRegistryMixin";
        String targetOwner = "net/minecraft/class_7924";
        byte[] originalJar = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                mixinClass + ".class", classWithMixinTarget(mixinClass, targetOwner),
                "mixins/registry.mixins.json", """
                        {
                          "required": true,
                          "package": "net.fabricmc.fabric.mixin.registry",
                          "refmap": "custom-registry-map.json",
                          "mixins": ["LegacyRegistryMixin"]
                        }
                        """.getBytes(StandardCharsets.UTF_8),
                "custom-registry-map.json", """
                        {
                          "mappings": {
                            "net.fabricmc.fabric.mixin.registry.LegacyRegistryMixin": {
                              "method_2345(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;": "Lnet/minecraft/class_7924;method_2345(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;"
                            }
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8)
        ));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenMixinRefmaps());
        assertArrayEquals(originalJar, Files.readAllBytes(source),
                "The source Fabric API JAR must remain byte-for-byte unchanged.");

        byte[] stagedRefmap = readEntry(Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar")),
                "custom-registry-map.json");
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                new String(stagedRefmap, StandardCharsets.UTF_8)).getAsJsonObject();
        String mappedKey = "lookup(Lnet/minecraft/core/Registry;)Lnet/minecraft/core/Registry;";
        String mappedValue = "Lnet/minecraft/core/Registry;lookup(Lnet/minecraft/core/Registry;)Lnet/minecraft/core/Registry;";
        assertEquals(mappedValue, root.getAsJsonObject("mappings")
                .getAsJsonObject(mixinClass.replace('/', '.')).get(mappedKey).getAsString());
    }

    private static byte[] classWithMixinTarget(String className, String targetOwner) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                className, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", true);
        mixin.visit("value", org.objectweb.asm.Type.getObjectType(targetOwner));
        mixin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void rewritesNamedPlayerFieldReference() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-zombie"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] originalJar = jar(Map.of(
                "net/example/EntityHelper.class", classWithEntityTypeFieldReference("PLAYER")));
        Path source = modsDirectory.resolve("entity-helper.jar");
        Files.write(source, originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenReferences());
        byte[] stagedClass = readEntry(Files.readAllBytes(result.modsDirectory().resolve("entity-helper.jar")),
                "net/example/EntityHelper.class");
        assertEquals("net/minecraft/world/entity/EntityTypes", firstFieldOwner(stagedClass));
        assertArrayEquals(originalJar, Files.readAllBytes(source));
    }

    @Test
    void doesNotCreateAStagedDirectoryWhenNoMovedRegistryReferenceIsFound() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-no-change"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] originalJar = jar(Map.of(
                "net/example/OtherMod.class", classWithNoFieldReferences()));
        Files.write(modsDirectory.resolve("other-mod.jar"), originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertFalse(result.transformed());
        assertEquals(modsDirectory.toAbsolutePath().normalize(), result.modsDirectory());
        assertFalse(Files.exists(gameDirectory.resolve(".gafiloader/compat-mods")));
        assertArrayEquals(originalJar, Files.readAllBytes(modsDirectory.resolve("other-mod.jar")));
    }

    @Test
    void reportsWhenAClassCannotBeInspectedInsteadOfSilentlyTreatingItAsCompatible() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-unreadable-class"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] originalJar = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"ordinary-mod","version":"1.0.0"}
                        """.getBytes(StandardCharsets.UTF_8),
                "net/example/Broken.class", new byte[] { 0x01, 0x02, 0x03, 0x04 }
        ));
        Path source = modsDirectory.resolve("ordinary-mod.jar");
        Files.write(source, originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertFalse(result.transformed());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("Could not inspect class net/example/Broken.class")));
        assertArrayEquals(originalJar, Files.readAllBytes(source),
                "Inspection diagnostics must never modify the original mod archive.");
    }

    private static byte[] classWithEntityTypeFieldReference(String fieldName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/Generated", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "entityType", "()Lnet/minecraft/world/entity/EntityType;", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/EntityType",
                fieldName, "Lnet/minecraft/world/entity/EntityType;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithIntermediaryEntityTypePlayerReference() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/IntermediaryGenerated", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "entityType", "()Lnet/minecraft/class_1299;", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_1299",
                "field_6097", "Lnet/minecraft/class_1299;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private LegacyClassTweakerMappings createBytecodeMappings() throws IOException {
        Path intermediaryJar = temporaryDirectory.resolve("intermediary-bytecode-fixture.jar");
        byte[] tiny = ("tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_7924\n"
                + "\tf\tLa;\tb\tfield_1234\n"
                + "\tm\t(La;)La;\tc\tmethod_2345\n"
                + "c\tb\tnet/minecraft/class_9001\n"
                + "c\tc\tnet/minecraft/class_310\n"
                + "\tm\t(Ld;)V\ta\tmethod_1507\n"
                + "\tm\t()V\tb\tmethod_1574\n"
                + "\tm\t()V\tc\tmethod_29610\n"
                + "\tm\t(Z)V\td\tmethod_1523\n"
                + "c\td\tnet/minecraft/class_437\n"
                + "\tm\t()V\taZ_\tmethod_25432\n"
                + "\tm\t()V\te\tmethod_25393\n"
                + "c\te\tnet/minecraft/class_3928\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(intermediaryJar, jar(Map.of("mappings/mappings.tiny", tiny)));

        Path mojangMappings = temporaryDirectory.resolve("client-mappings-bytecode-fixture.txt");
        Files.writeString(mojangMappings, """
                net.minecraft.core.Registry -> a:
                    net.minecraft.core.Registry entry -> b
                    net.minecraft.core.Registry lookup(net.minecraft.core.Registry) -> c
                net.minecraft.SystemReport -> b:
                net.minecraft.client.Minecraft -> c:
                    void setScreen(net.minecraft.client.gui.screens.Screen) -> a
                    void tick() -> b
                    void doWorldLoad() -> c
                    void runTick(boolean) -> d
                net.minecraft.client.gui.screens.Screen -> d:
                    void removed() -> aZ_
                    void tick() -> e
                net.minecraft.client.gui.screens.LevelLoadingScreen -> e:
                """, StandardCharsets.UTF_8);
        return LegacyClassTweakerMappings.load(intermediaryJar, mojangMappings);
    }

    private static String atTargetForMethod(byte[] classBytes, String requestedMethod) {
        AtomicReference<String> target = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!requestedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotationDescriptor)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"at".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String ignored, String nestedDescriptor) {
                                        if (!"Lorg/spongepowered/asm/mixin/injection/At;".equals(nestedDescriptor)) {
                                            return null;
                                        }
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visit(String propertyName, Object value) {
                                                if ("target".equals(propertyName) && value instanceof String selector) {
                                                    target.set(selector);
                                                }
                                            }
                                        };
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, 0);
        return target.get();
    }

    private static Set<String> injectMethodTargetsForMethod(byte[] classBytes, String requestedMethod) {
        Set<String> targets = new LinkedHashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!requestedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotationDescriptor)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"method".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String selector) targets.add(selector);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Set.copyOf(targets);
    }

    private static byte[] classWithLegacyScreenApiMixin() {
        String mixinClass = "net/fabricmc/fabric/mixin/screen/MinecraftMixin";
        String callbackInfo = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
        String screenDescriptor = "Lnet/minecraft/class_437;";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                mixinClass, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_ANNOTATION, true);
        mixin.visit("value", Type.getObjectType("net/minecraft/class_310"));
        mixin.visitEnd();

        FieldVisitor screen = writer.visitField(Opcodes.ACC_PUBLIC, "screen",
                screenDescriptor, null, null);
        screen.visitAnnotation(SHADOW_ANNOTATION, true).visitEnd();
        screen.visitEnd();

        FieldVisitor gameThread = writer.visitField(Opcodes.ACC_PRIVATE, "gameThread",
                "Ljava/lang/Thread;", null, null);
        gameThread.visitAnnotation(SHADOW_ANNOTATION, true).visitEnd();
        gameThread.visitEnd();

        FieldVisitor tickingScreen = writer.visitField(Opcodes.ACC_PRIVATE, "tickingScreen",
                screenDescriptor, null, null);
        tickingScreen.visitEnd();

        addScreenApiHandler(writer, mixinClass, "checkThreadOnDev",
                "(" + screenDescriptor + callbackInfo + ")V", null, screenDescriptor, "screen");
        addScreenApiHandler(writer, mixinClass, "onScreenRemove",
                "(" + screenDescriptor + callbackInfo + ")V", "method_1507", screenDescriptor, "screen");
        addScreenApiHandler(writer, mixinClass, "onScreenRemoveBecauseStopping",
                "(" + callbackInfo + ")V", "destroy", screenDescriptor, "screen");
        addScreenApiHandler(writer, mixinClass, "beforeScreenTick",
                "(" + callbackInfo + ")V", "method_1574", screenDescriptor, "screen");
        addScreenApiHandler(writer, mixinClass, "afterScreenTick",
                "(" + callbackInfo + ")V", "method_1574", screenDescriptor, "tickingScreen");
        addScreenApiHandler(writer, mixinClass, "beforeLoadingScreenTick",
                "(" + callbackInfo + ")V", "method_29610", screenDescriptor, "screen");
        addScreenApiHandler(writer, mixinClass, "afterLoadingScreenTick",
                "(" + callbackInfo + ")V", "method_29610", screenDescriptor, "tickingScreen");

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addScreenApiHandler(ClassWriter writer, String mixinClass, String name,
            String descriptor, String target, String screenDescriptor, String fieldName) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name, descriptor, null, null);
        if (target != null) {
            AnnotationVisitor inject = method.visitAnnotation(
                    "Lorg/spongepowered/asm/mixin/injection/Inject;", true);
            AnnotationVisitor targets = inject.visitArray("method");
            targets.visit(null, target);
            targets.visitEnd();
            String atTarget = switch (name) {
                case "onScreenRemove" ->
                        "Lnet/minecraft/class_437;method_25432()V";
                case "beforeScreenTick", "afterScreenTick" ->
                        "Lnet/minecraft/class_437;method_25393()V";
                case "beforeLoadingScreenTick" ->
                        "Lnet/minecraft/class_3928;method_25393()V";
                case "afterLoadingScreenTick" ->
                        "Lnet/minecraft/class_310;method_1523(Z)V";
                default -> null;
            };
            if (atTarget != null) {
                // @Inject.at is declared as At[]; model the real class-file array
                // encoding so the remapper test covers nested annotation traversal.
                AnnotationVisitor atArray = inject.visitArray("at");
                AnnotationVisitor at = atArray.visitAnnotation(null,
                        "Lorg/spongepowered/asm/mixin/injection/At;");
                at.visit("value", "INVOKE");
                at.visit("target", atTarget);
                if ("onScreenRemove".equals(name) || "afterScreenTick".equals(name)
                        || "afterLoadingScreenTick".equals(name)) {
                    at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER");
                }
                at.visitEnd();
                atArray.visitEnd();
            }
            inject.visitEnd();
        }
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, mixinClass, fieldName, screenDescriptor);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static byte[] classWithLegacySystemReportMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                SYSTEM_REPORT_MIXIN_CLASS, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_ANNOTATION, true);
        mixin.visit("value", Type.getObjectType(SYSTEM_REPORT_INTERMEDIARY_OWNER));
        mixin.visitEnd();

        MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "method_9002", LEGACY_SYSTEM_REPORT_DETAIL_DESCRIPTOR, null, null);
        shadow.visitAnnotation(SHADOW_ANNOTATION, true).visitEnd();
        shadow.visitEnd();

        MethodVisitor fill = writer.visitMethod(Opcodes.ACC_PRIVATE, "fillSystemDetails", "()V", null, null);
        fill.visitCode();
        fill.visitVarInsn(Opcodes.ALOAD, 0);
        fill.visitLdcInsn("Fabric Mods");
        fill.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESTATIC, SYSTEM_REPORT_MIXIN_CLASS,
                        "lambda$fillSystemDetails$0", "()Ljava/lang/String;", false),
                Type.getMethodType("()Ljava/lang/String;"));
        fill.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SYSTEM_REPORT_MIXIN_CLASS,
                "method_9002", LEGACY_SYSTEM_REPORT_DETAIL_DESCRIPTOR, false);
        fill.visitInsn(Opcodes.RETURN);
        fill.visitMaxs(3, 1);
        fill.visitEnd();

        MethodVisitor lambda = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$fillSystemDetails$0", "()Ljava/lang/String;", null, null);
        lambda.visitCode();
        lambda.visitLdcInsn("Fabric Mods");
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(1, 0);
        lambda.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithIntermediaryRegistryReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/LegacyApiEntrypoint", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "registry", "Lnet/minecraft/class_7924;", null, null)
                .visitEnd();

        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;", null, null);
        lookup.visitCode();
        lookup.visitVarInsn(Opcodes.ALOAD, 0);
        lookup.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_7924", "method_2345",
                "(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(1, 1);
        lookup.visitEnd();

        MethodVisitor field = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "field", "()Lnet/minecraft/class_7924;", null, null);
        field.visitCode();
        field.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_7924", "field_1234",
                "Lnet/minecraft/class_7924;");
        field.visitInsn(Opcodes.ARETURN);
        field.visitMaxs(1, 0);
        field.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithNoFieldReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/NoReferences", null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String firstFieldOwner(byte[] classBytes) {
        AtomicReference<String> owner = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
                                               String fieldDescriptor) {
                        if (owner.get() == null) owner.set(fieldOwner);
                    }
                };
            }
        }, 0);
        return owner.get();
    }

    private static String firstFieldName(byte[] classBytes) {
        AtomicReference<String> name = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (name.get() == null) name.set(fieldName);
                    }
                };
            }
        }, 0);
        return name.get();
    }

    private static String firstFieldDescriptor(byte[] classBytes) {
        AtomicReference<String> descriptor = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (descriptor.get() == null) descriptor.set(fieldDescriptor);
                    }
                };
            }
        }, 0);
        return descriptor.get();
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] readEntry(byte[] archive, String requestedEntry) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(requestedEntry)) {
                    return input.readAllBytes();
                }
            }
        }
        throw new IOException("Missing test archive entry: " + requestedEntry);
    }

    private static boolean hasEntry(byte[] archive, String requestedEntry) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(requestedEntry)) return true;
            }
        }
        return false;
    }
}
