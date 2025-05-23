/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.TestDataFile;
import kotlin.collections.ArraysKt;
import kotlin.collections.CollectionsKt;
import kotlin.io.FilesKt;
import kotlin.text.Charsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.TestHelperGeneratorKt;
import org.jetbrains.kotlin.TestsCompilerError;
import org.jetbrains.kotlin.TestsCompiletimeError;
import org.jetbrains.kotlin.checkers.utils.CheckerTestUtil;
import org.jetbrains.kotlin.cli.common.output.OutputUtilsKt;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace;
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.JvmTarget;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider;
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper;
import org.jetbrains.kotlin.test.*;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.utils.ExceptionUtilsKt;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.jetbrains.kotlin.codegen.CodegenTestUtil.*;
import static org.jetbrains.kotlin.codegen.CodegenTestUtilsKt.runBoxMethod;
import static org.jetbrains.kotlin.test.util.KtTestUtil.getAnnotationsJar;

public abstract class CodegenTestCase extends KotlinBaseTest<KotlinBaseTest.TestFile> {
    private static final String DEFAULT_TEST_FILE_NAME = "a_test";
    private static final String DEFAULT_JVM_TARGET = System.getProperty("kotlin.test.default.jvm.target");

    protected KotlinCoreEnvironment myEnvironment;
    protected CodegenTestFiles myFiles;
    protected ClassFileFactory classFileFactory;
    protected GeneratedClassLoader initializedClassLoader;
    protected File javaClassesOutputDirectory = null;
    protected List<File> additionalDependencies = null;

    protected ConfigurationKind configurationKind = ConfigurationKind.JDK_ONLY;

    protected final void createEnvironmentWithMockJdkAndIdeaAnnotations(
            @NotNull ConfigurationKind configurationKind,
            @NotNull File... javaSourceRoots
    ) {
        createEnvironmentWithMockJdkAndIdeaAnnotations(configurationKind, Collections.emptyList(), TestJdkKind.MOCK_JDK, javaSourceRoots);
    }

    protected final void createEnvironmentWithMockJdkAndIdeaAnnotations(
            @NotNull ConfigurationKind configurationKind,
            @NotNull List<TestFile> testFilesWithConfigurationDirectives,
            @NotNull TestJdkKind testJdkKind,
            @NotNull File... javaSourceRoots
    ) {
        if (myEnvironment != null) {
            throw new IllegalStateException("must not set up myEnvironment twice");
        }

        CompilerConfiguration configuration = createConfiguration(
                configurationKind,
                testJdkKind,
                Collections.singletonList(getAnnotationsJar()),
                ArraysKt.filterNotNull(javaSourceRoots),
                testFilesWithConfigurationDirectives
        );

        myEnvironment = KotlinCoreEnvironment.createForTests(
                getTestRootDisposable(), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
        );

        setupEnvironment(myEnvironment);
    }

    @Override
    protected void tearDown() throws Exception {
        myFiles = null;
        myEnvironment = null;
        classFileFactory = null;

        if (initializedClassLoader != null) {
            initializedClassLoader.dispose();
            initializedClassLoader = null;
        }

        super.tearDown();
    }

    protected void loadText(@NotNull String text) {
        myFiles = CodegenTestFiles.create(DEFAULT_TEST_FILE_NAME + ".kt", text, myEnvironment.getProject());
    }

    @NotNull
    protected String loadFile(@NotNull @TestDataFile String name) {
        return loadFileByFullPath(KtTestUtil.getTestDataPathBase() + "/codegen/" + name);
    }

    @NotNull
    protected String loadFileByFullPath(@NotNull String fullPath) {
        try {
            File file = new File(fullPath);
            String content = FileUtil.loadFile(file, Charsets.UTF_8.name(), true);
            assert myFiles == null : "Should not initialize myFiles twice";
            myFiles = CodegenTestFiles.create(file.getName(), content, myEnvironment.getProject());
            return content;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void loadFiles(@NotNull String... names) {
        List<KtFile> files = new ArrayList<>(names.length);
        for (String name : names) {
            try {
                String content = KtTestUtil.doLoadFile(KtTestUtil.getTestDataPathBase() + "/codegen/", name);
                KtFile file = KtTestUtil.createFile(name, content, myEnvironment.getProject());
                files.add(file);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        myFiles = CodegenTestFiles.create(files);
    }

    protected void loadFile() {
        loadFile(getPrefix() + "/" + getTestName(true) + ".kt");
    }

    @NotNull
    private static CodegenTestFiles loadMultiFiles(@NotNull List<TestFile> files, @NotNull Project project) {
        Collections.sort(files);

        List<KtFile> ktFiles = new ArrayList<>(files.size());
        for (TestFile file : files) {
            if (file.name.endsWith(".kt") || file.name.endsWith(".kts")) {
                // `rangesToDiagnosticNames` parameter is not-null only for diagnostic tests, it's using for lazy diagnostics
                String content = CheckerTestUtil.INSTANCE.parseDiagnosedRanges(file.content, new ArrayList<>(0), null);
                ktFiles.add(KtTestUtil.createFile(file.name, content, project));
            }
        }

        return CodegenTestFiles.create(ktFiles);
    }

    @NotNull
    protected String getPrefix() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    protected GeneratedClassLoader generateAndCreateClassLoader(boolean reportProblems) {
        if (initializedClassLoader != null) {
            fail("Double initialization of class loader in same test");
        }

        initializedClassLoader = createClassLoader();

        if (!CodegenTestUtil.verifyAllFilesWithAsm(generateClassesInFile(reportProblems), reportProblems)) {
            fail("Verification failed: see exceptions above");
        }

        return initializedClassLoader;
    }

    @NotNull
    protected GeneratedClassLoader createClassLoader() {
        ClassLoader classLoader;
        if (configurationKind.getWithReflection()) {
            classLoader = ForTestCompileRuntime.runtimeAndReflectJarClassLoader();
        }
        else {
            classLoader = ForTestCompileRuntime.runtimeJarClassLoader();
        }

        return new GeneratedClassLoader(
                generateClassesInFile(),
                classLoader,
                getClassPathURLs()
        );
    }

    @NotNull
    private URL[] getClassPathURLs() {
        List<File> files = new ArrayList<>();
        if (javaClassesOutputDirectory != null) {
            files.add(javaClassesOutputDirectory);
        }
        if (additionalDependencies != null) {
            files.addAll(additionalDependencies);
        }

        ScriptConfigurationsProvider externalImportsProvider =
                ScriptConfigurationsProvider.Companion.getInstance(myEnvironment.getProject());
        if (externalImportsProvider != null) {
            myEnvironment.getSourceFiles().forEach(
                    file -> {
                        ScriptCompilationConfigurationWrapper refinedConfiguration = externalImportsProvider.getScriptConfiguration(file);
                        if (refinedConfiguration != null) {
                            files.addAll(refinedConfiguration.getDependenciesClassPath());
                        }
                    }
            );
        }

        try {
            URL[] result = new URL[files.size()];
            for (int i = 0; i < files.size(); i++) {
                result[i] = files.get(i).toURI().toURL();
            }
            return result;
        }
        catch (MalformedURLException e) {
            throw ExceptionUtilsKt.rethrow(e);
        }
    }

    @NotNull
    protected String generateToText() {
        if (classFileFactory == null) {
            classFileFactory = GenerationUtils.compileFiles(myFiles.getPsiFiles(), myEnvironment).getFactory();
        }
        return classFileFactory.createText(null);
    }

    @NotNull
    protected Class<?> generateFacadeClass() {
        FqName facadeClassFqName = JvmFileClassUtil.getFileClassInfoNoResolve(myFiles.getPsiFile()).getFacadeClassFqName();
        return generateClass(facadeClassFqName.asString());
    }

    @NotNull
    protected Class<?> generateClass(@NotNull String name) {
        try {
            //noinspection resource
            return generateAndCreateClassLoader(true).loadClass(name);
        }
        catch (ClassNotFoundException e) {
            fail("No class file was generated for: " + name);
            return null;
        }
    }

    @NotNull
    protected ClassFileFactory generateClassesInFile() {
        return generateClassesInFile(true);
    }

    @NotNull
    private ClassFileFactory generateClassesInFile(boolean reportProblems) {
        if (classFileFactory != null) return classFileFactory;

        try {
            GenerationState generationState = GenerationUtils.compileFiles(
                    myFiles.getPsiFiles(), myEnvironment, ClassBuilderFactories.TEST,
                    new NoScopeRecordCliBindingTrace(myEnvironment.getProject())
            );
            classFileFactory = generationState.getFactory();

            // Some names are not allowed in the dex file format and the VM will reject the program
            // if they are used. Therefore, a few tests cannot be dexed as they use such names that
            // are valid on the JVM but not on the Android Runtime.
            boolean ignoreDexing = myFiles.getPsiFiles().stream().anyMatch(
                it -> InTextDirectivesUtils.isDirectiveDefined(it.getText(), "IGNORE_DEXING")
            );
            if (D8Checker.RUN_D8_CHECKER && !ignoreDexing) {
                D8Checker.check(classFileFactory);
            }
        }
        catch (TestsCompiletimeError e) {
            if (reportProblems) {
                e.getOriginal().printStackTrace();
                generateInstructionsAsText();
                System.err.println("See exceptions above");
            }
            else {
                System.err.println("Compilation failure");
            }
            throw e;
        }
        catch (Throwable e) {
            if (reportProblems) {
                generateInstructionsAsText();
            }
            throw new TestsCompilerError(e);
        }
        return classFileFactory;
    }

    private void generateInstructionsAsText() {
        System.err.println("Generating instructions as text...");
        try {
            if (classFileFactory == null) {
                System.err.println("Cannot generate text: exception was thrown during generation");
            }
            else {
                System.err.println(classFileFactory.createText());
            }
        }
        catch (Throwable e1) {
            System.err.println("Exception thrown while trying to generate text, the actual exception follows:");
            e1.printStackTrace();
            System.err.println("-----------------------------------------------------------------------------");
        }
    }

    @NotNull
    protected Method generateFunction() {
        Class<?> aClass = generateFacadeClass();
        try {
            return findTheOnlyMethod(aClass);
        }
        catch (Error e) {
            System.out.println(generateToText());
            throw e;
        }
    }

    @NotNull
    protected Method generateFunction(@NotNull String name) {
        return findDeclaredMethodByName(generateFacadeClass(), name);
    }

    @Override
    protected void updateConfiguration(@NotNull CompilerConfiguration configuration) {
        setCustomDefaultJvmTarget(configuration);
        configureIrFir(configuration);
    }

    private static void setCustomDefaultJvmTarget(CompilerConfiguration configuration) {
        if (DEFAULT_JVM_TARGET != null) {
            JvmTarget customDefaultTarget = JvmTarget.fromString(DEFAULT_JVM_TARGET);
            assert customDefaultTarget != null : "Can't construct JvmTarget for " + DEFAULT_JVM_TARGET;
            JvmTarget originalTarget = configuration.get(JVMConfigurationKeys.JVM_TARGET);
            if (originalTarget == null || customDefaultTarget.getMajorVersion() > originalTarget.getMajorVersion()) {
                // It's not safe to substitute target in general
                // cause it can affect generated bytecode and original behaviour should be tested somehow.
                // Original behaviour testing is perfomed by
                //
                //      codegenTest(target = 6, jvm = "Last", jdk = mostRecentJdk)
                //      codegenTest(target = 8, jvm = "Last", jdk = mostRecentJdk)
                //
                // in compiler/tests-different-jdk/build.gradle.kts
                configuration.put(JVMConfigurationKeys.JVM_TARGET, customDefaultTarget);
            }
        }
    }

    protected void compile(@NotNull List<TestFile> files) {
        compile(files, true);
    }

    protected void compile(@NotNull List<TestFile> files, boolean reportProblems) {
        File javaSourceDir = writeJavaFiles(files);

        configurationKind = extractConfigurationKind(files);

        CompilerConfiguration configuration = createConfiguration(
                configurationKind, getTestJdkKind(files),
                Collections.singletonList(getAnnotationsJar()),
                ArraysKt.filterNotNull(new File[] {javaSourceDir}),
                files
        );

        myEnvironment = KotlinCoreEnvironment.createForTests(
                getTestRootDisposable(), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
        );
        setupEnvironment(myEnvironment);

        myFiles = loadMultiFiles(files, myEnvironment.getProject());

        generateClassesInFile(reportProblems);

        boolean compileJavaFiles = javaSourceDir != null && javaClassesOutputDirectory == null;
        File kotlinOut = null;

        // If there are Java files, they should be compiled against the class files produced by Kotlin, so we dump them to the disk
        if (compileJavaFiles) {
            kotlinOut = createTempDirectory(toString());
            OutputUtilsKt.writeAllTo(classFileFactory, kotlinOut);
        }

        javaClassesOutputDirectory = null;
        if (compileJavaFiles) {
            javaClassesOutputDirectory = createTempDirectory("java-classes");
            List<String> javacOptions = extractJavacOptions(
                    files,
                    configuration.get(JVMConfigurationKeys.JVM_TARGET),
                    configuration.getBoolean(JVMConfigurationKeys.ENABLE_JVM_PREVIEW)
            );
            boolean isJava9Module = false; // No Java modules in legacy tests
            List<String> finalJavacOptions = prepareJavacOptions(
                    Collections.singletonList(kotlinOut.getPath()), javacOptions, javaClassesOutputDirectory, isJava9Module
            );

            JvmCompilationUtils.compileJavaFiles(
                    findJavaSourcesInDirectory(javaSourceDir).stream().map(File::new).collect(Collectors.toList()),
                    finalJavacOptions
            ).assertSuccessful();
        }
    }

    @NotNull
    private static List<String> extractJavacOptions(
            @NotNull List<TestFile> files,
            @Nullable JvmTarget kotlinTarget,
            boolean isJvmPreviewEnabled
    ) {
        List<String> javacOptions = new ArrayList<>(0);
        for (TestFile file : files) {
            javacOptions.addAll(InTextDirectivesUtils.findListWithPrefixes(file.content, "// JAVAC_OPTIONS:"));
        }

        if (kotlinTarget != null) {
            if (isJvmPreviewEnabled) {
                javacOptions.add("--release");
                javacOptions.add(kotlinTarget.getDescription());
                javacOptions.add("--enable-preview");
            } else {
                javacOptions.add("-source");
                javacOptions.add(kotlinTarget.getDescription());
                javacOptions.add("-target");
                javacOptions.add(kotlinTarget.getDescription());
            }
        }

        return javacOptions;
    }

    @NotNull
    @Override
    public final TargetBackend getBackend() {
        return TargetBackend.JVM_IR;
    }

    @Override
    protected void doTest(@NotNull String filePath) {
        File file = new File(filePath);

        String expectedText = KtTestUtil.doLoadFile(file);
        List<TestFile> testFiles = createTestFilesFromFile(file, expectedText);

        try {
            doMultiFileTest(file, testFiles);
        } catch (Exception e) {
            throw ExceptionUtilsKt.rethrow(e);
        }
    }

    @Override
    @NotNull
    protected List<TestFile> createTestFilesFromFile(@NotNull File file, @NotNull String expectedText) {
        List<TestFile> testFiles =
                TestFiles.createTestFiles(file.getName(), expectedText, new TestFiles.TestFileFactoryNoModules<TestFile>() {
                    @NotNull
                    @Override
                    public TestFile create(@NotNull String fileName, @NotNull String text, @NotNull Directives directives) {
                        return new TestFile(fileName, text, directives);
                    }
                }, false, parseDirectivesPerFiles());
        if (InTextDirectivesUtils.isDirectiveDefined(expectedText, "WITH_HELPERS")) {
            testFiles.add(new TestFile("CodegenTestHelpers.kt", TestHelperGeneratorKt.createTextForCodegenTestHelpers(getBackend())));
        }
        return testFiles;
    }

    @NotNull
    private static File createTempDirectory(String prefix) {
        try {
            return KtTestUtil.tmpDir(prefix);
        } catch (IOException e) {
            throw ExceptionUtilsKt.rethrow(e);
        }
    }

    @Nullable
    private static File writeJavaFiles(@NotNull List<TestFile> files) {
        List<TestFile> javaFiles = CollectionsKt.filter(files, file -> file.name.endsWith(".java"));
        if (javaFiles.isEmpty()) return null;

        File dir = createTempDirectory("java-files");

        for (TestFile testFile : javaFiles) {
            File file = new File(dir, testFile.name);
            KtTestUtil.mkdirs(file.getParentFile());
            FilesKt.writeText(file, testFile.content, Charsets.UTF_8);
        }

        return dir;
    }

    protected static void callBoxMethodAndCheckResult(URLClassLoader classLoader, Method method, boolean unexpectedBehaviour) {
        ClassLoader savedClassLoader = Thread.currentThread().getContextClassLoader();
        if (savedClassLoader != classLoader) {
            // otherwise the test infrastructure used in the test may conflict with the one from the context classloader
            Thread.currentThread().setContextClassLoader(classLoader);
        }
        String result;
        try {
            result = runBoxMethod(method);
        }
        finally {
            if (savedClassLoader != classLoader) {
                Thread.currentThread().setContextClassLoader(savedClassLoader);
            }
        }
        if (unexpectedBehaviour) {
            assertNotSame("OK", result);
        } else {
            assertEquals("OK", result);
        }
    }

    protected void printReport(File wholeFile) {
        boolean isIgnored = InTextDirectivesUtils.isIgnoredTarget(
                getBackend(), wholeFile, InTextDirectivesUtils.IGNORE_BACKEND_DIRECTIVE_PREFIXES
        );
        if (!isIgnored) {
            System.out.println(generateToText());
        }
    }
}
