package org.jerkar.tool;

import org.jerkar.api.depmanagement.JkComputedDependency;
import org.jerkar.api.depmanagement.JkDependencies;
import org.jerkar.api.depmanagement.JkDependencyResolver;
import org.jerkar.api.file.JkFileTree;
import org.jerkar.api.system.JkLog;
import org.jerkar.api.utils.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

/**
 * Base class defining commons tasks and utilities necessary for building any
 * kind of project, regardless involved technologies.
 *
 * @author Jerome Angibaud
 */
public class JkBuild {

    private static final ThreadLocal<Map<ImportedBuildRef, JkBuild>> IMPORTED_BUILD_CONTEXT = new ThreadLocal<>();

    private static final ThreadLocal<File> BASE_DIR_CONTEXT = new ThreadLocal<>();

    static void baseDirContext(File baseDir) {
        BASE_DIR_CONTEXT.set(baseDir);
    }

    private final File baseDir;

    private final Instant buildTime = Instant.now();

    /** attached plugin instances to this build */
    public final JkBuildPlugins plugins = new JkBuildPlugins(this);

    private JkDependencyResolver buildDefDependencyResolver;

    private JkDependencies buildDependencies;

    private final JkImportedBuilds importedBuilds;

    private JkScaffolder scaffolder;

    // ------------------ options --------------------------------------------------------


    @JkDoc("Help options")
    private final JkHelpOptions help = new JkHelpOptions();

    @JkDoc("Embed Jerkar jar along bin script in the project while scaffolding so the project can be run without Jerkar installed.")
    boolean scaffoldEmbed;

    // --------------------------- constructs ----------------------------------

    /**
     * Constructs a {@link JkBuild}
     */
    public JkBuild() {
        final File baseDirContext = BASE_DIR_CONTEXT.get();
        JkLog.trace("Initializing " + this.getClass().getName() + " instance with base dir context : " + baseDirContext);
        this.baseDir = JkUtilsObject.firstNonNull(baseDirContext, JkUtilsFile.workingDir());
        JkLog.trace("Initializing " + this.getClass().getName() + " instance with base dir  : " + this.baseDir);
        final List<JkBuild> directImportedBuilds = getDirectImportedBuilds();
        this.importedBuilds = JkImportedBuilds.of(this.baseTree().root(), directImportedBuilds);
    }


    /**
     * This method is invoked right after the option values has been injected to instance fields
     * of this object.
     */
    public void init() {
        // Do nothing by default
    }

    // -------------------------------- basic functionalities ---------------------------------------

    /**
     * Returns the time the build was started.
     */
    protected Instant buildTime() {
        return buildTime;
    }

    /**
     * Returns the base directory for this project. All file/directory path are
     * resolved to this directory.
     */
    public final JkFileTree baseTree() {
        return JkFileTree.of(baseDir);
    }

    /**
     * Short-hand for <code>baseTree().file(relativePath)</code>.
     */
    public final File file(String relativePath) {
        if (relativePath.isEmpty()) {
            return baseTree().root();
        }
        return baseTree().file(relativePath);
    }

    /**
     * The output directory where all the final and intermediate artifacts are
     * generated.
     */
    public JkFileTree ouputTree() {
        return baseTree().go(JkConstants.BUILD_OUTPUT_PATH).createIfNotExist();
    }

    /**
     * Short-hand for <code>ouputTree().file(relativePath)</code>.
     */
    public File ouputFile(String relativePath) {
        return ouputTree().file(relativePath);
    }

    /**
     * Returns a formatted string providing information about this build definition.
     */
    public String infoString() {
        return "base directory : " + this.baseTree() + "\n"
                + "imported builds : " + this.importedBuilds.directs();
    }

    /**
     * Returns the scaffolder object in charge of doing the scaffolding for this build.
     * Override this method if you write a template class that need to do custom action for scaffolding.
     */
    public JkScaffolder scaffolder() {
        if (this.scaffolder == null) {
            this.scaffolder = new JkScaffolder(this.baseDir, this.scaffoldEmbed);
        }
        return this.scaffolder;
    }

    // ------------------------------ build dependencies ---------------------------------------------

    void setBuildDefDependencyResolver(JkDependencies buildDependencies, JkDependencyResolver scriptDependencyResolver) {
        this.buildDependencies = buildDependencies;
        this.buildDefDependencyResolver = scriptDependencyResolver;
    }

    /**
     * Returns the dependency resolver used to compile/run scripts of this
     * project.
     */
    public JkDependencyResolver buildDependencyResolver() {
        return this.buildDefDependencyResolver;
    }

    /**
     * Dependencies necessary to compile the this build class. It is not the dependencies for building the project.
     */
    public JkDependencies buildDependencies() {
        return buildDependencies;
    }

    // --------------------------- plugins ----------------------------------------------------

    /**
     * Returns the classes accepted as template for plugins. If you override it,
     * do not forget to add the ones to the super class.
     */
    protected List<Class<Object>> pluginTemplateClasses() {
        return Collections.emptyList();
    }

    /**
     * Set the plugins to activate for this build. This method should be invoked
     * after the base directory has been set, so plugins can be configured
     * using the proper base dir.
     */
    protected void setPlugins(Iterable<?> plugins) {
        // Do nothing as no plugin extension as been defined at this level.
    }

    /**
     * Returns plugins attached to this build and extending the specified class.
     */
    public <T extends JkBuildPlugin> T pluginOf(Class<T> pluginClass) {
        return this.plugins.findInstanceOf(pluginClass);
    }

    // ------------------------------ Command line methods ---------------------------------------------------

    /**
     * Creates the project structure (mainly project folder layout, build class code and IDE metadata) at the asScopedDependency
     * of the current project.
     */
    @JkDoc("Creates the project structure")
    public final void scaffold() {
        scaffolder().run();
        JkBuildPlugin.applyScaffold(this.plugins.getActives());
    }


    /** Clean the output directory. */
    @JkDoc("Cleans the output directory.")
    public void clean() {
        JkLog.start("Cleaning output directory " + ouputTree().root().getPath());
        ouputTree().exclude(JkConstants.BUILD_DEF_BIN_DIR_NAME + "/**").deleteAll();
        JkLog.done();
    }

    /** Conventional method standing for the default operations to perform. */
    @JkDoc("Conventional method standing for the default operations to perform.")
    public void doDefault() {
        clean();
    }

    /** Run checks to verify the package is valid and meets quality criteria. */
    @JkDoc("Runs checks to verify the project is valid and meets quality criteria.")
    public void verify() {
        JkBuildPlugin.applyVerify(this.plugins.getActives());
    }

    /** Displays all available methods defined in this build. */
    @JkDoc("Displays all available methods defined in this build.")
    public void help() {
        if (help.xml || help.xmlFile != null) {
            final Document document = JkUtilsXml.createDocument();
            final Element buildEl = ProjectDef.ProjectBuildClassDef.of(this).toElement(document);
            document.appendChild(buildEl);
            if (help.xmlFile == null) {
                JkUtilsXml.output(document, System.out);
            } else {
                JkUtilsFile.createFileIfNotExist(help.xmlFile);
                final OutputStream os = JkUtilsIO.outputStream(help.xmlFile, false);
                JkUtilsXml.output(document, os);
                JkUtilsIO.closeQuietly(os);
                JkLog.info("Xml help file generated at " + help.xmlFile.getPath());
            }
        } else {
            HelpDisplayer.help(this);
        }

    }

    /** Displays details on all available plugins. */
    @JkDoc("Displays details on all available plugins.")
    public void helpPlugins() {
        HelpDisplayer.helpPlugins();
    }

    /** Displays meaningful information about this build. */
    @JkDoc("Displays meaningful information about this build.")
    public final void info() {
        JkLog.info(infoString());
    }


   // ----------------------------- being a dependency ---------------------------------------

    /**
     * Returns a {@link JkComputedDependency} on this project and specified
     * files. The 'doDefault' method will be invoked to compute the dependee
     * files.
     */
    protected JkComputedDependency asDependency(Iterable<File> files) {
        return BuildDependency.of(this, JkUtilsIterable.listWithoutDuplicateOf(files));
    }

    /**
     * Returns a {@link JkComputedDependency} on this project and specified
     * files. The 'doDefault' method will be invoked to compute the dependee
     * files.
     */
    public JkComputedDependency asDependency(File... files) {
        return BuildDependency.of(this, files);
    }

    /**
     * Returns a {@link JkComputedDependency} on this project and specified
     * files and methods to execute.
     */
    public JkComputedDependency asDependency(String methods, File... files) {
        return BuildDependency.of(this, methods, files);
    }

    // ----------------------------- Imported builds -------------------------------------------------

    /**
     * Returns imported builds with plugins applied on.
     */
    public final JkImportedBuilds importedBuilds() {
        final List<JkBuild> importedBuilds = JkBuildPlugin.applyPluginsToImportedBuilds(this.plugins.getActives(),
                this.importedBuilds.all());
        return JkImportedBuilds.of(this.baseTree().root(), importedBuilds);
    }

    @SuppressWarnings("unchecked")
    private List<JkBuild> getDirectImportedBuilds() {
        final List<JkBuild> result = new LinkedList<>();
        final List<Field> fields = JkUtilsReflect.getAllDeclaredField(this.getClass(),
                JkImportBuild.class);

        for (final Field field : fields) {
            final JkImportBuild jkProject = field.getAnnotation(JkImportBuild.class);
            final JkBuild subBuild = createImportedBuild(
                    (Class<? extends JkBuild>) field.getType(), jkProject.value());
            try {
                JkUtilsReflect.setFieldValue(this, field, subBuild);
            } catch (RuntimeException e) {
                File currentClassBaseDir = new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
                while (!new File(currentClassBaseDir, "build/def").exists() && currentClassBaseDir != null) {
                    currentClassBaseDir = currentClassBaseDir.getParentFile();
                }
                throw new IllegalStateException("Can't inject slave build instance of type " + subBuild.getClass().getSimpleName()
                        + " into field " + field.getDeclaringClass().getName()
                        + "#" + field.getName() + " from directory " + this.baseTree().root()
                        + "\nBuild class is located in " + currentClassBaseDir.getAbsolutePath()
                        + " while working dir is " + JkUtilsFile.workingDir()
                        + ".\nPlease set working dir to " + currentClassBaseDir.getPath(), e);
            }
            result.add(subBuild);
        }
        return result;
    }

    /**
     * Returns the build of the specified slave project. Slave projects are expressed with relative path to this project.
     */
    public JkBuild createImportedBuild(String relativePath) {
        return this.createImportedBuild(null, relativePath);
    }

    /**
     * Creates an instance of <code>JkBuild</code> for the given project and
     * build class. The instance field annotated with <code>JkOption</code> are
     * populated as usual.
     */
    @SuppressWarnings("unchecked")
    public <T extends JkBuild> T createImportedBuild(Class<T> clazz, String relativePath) {
        final File projectDir = this.file(relativePath);
        final ImportedBuildRef projectRef = new ImportedBuildRef(projectDir, clazz);
        Map<ImportedBuildRef, JkBuild> map = IMPORTED_BUILD_CONTEXT.get();
        if (map == null) {
            map = new HashMap<>();
            IMPORTED_BUILD_CONTEXT.set(map);
        }
        final T cachedResult = (T) IMPORTED_BUILD_CONTEXT.get().get(projectRef);
        if (cachedResult != null) {
            return cachedResult;
        }
        final Engine engine = new Engine(projectDir);
        final T result = engine.getBuild(clazz);
        JkOptions.populateFields(result);
        IMPORTED_BUILD_CONTEXT.get().put(projectRef, result);
        return result;
    }

    private static class ImportedBuildRef {

        final String canonicalFileName;

        final Class<?> clazz;

        ImportedBuildRef(File projectDir, Class<?> clazz) {
            super();
            this.canonicalFileName = JkUtilsFile.canonicalPath(projectDir);
            this.clazz = clazz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ImportedBuildRef that = (ImportedBuildRef) o;

            if (!canonicalFileName.equals(that.canonicalFileName)) return false;
            return clazz.equals(that.clazz);
        }

        @Override
        public int hashCode() {
            int result = canonicalFileName.hashCode();
            result = 31 * result + clazz.hashCode();
            return result;
        }
    }

    // -----------------------------------------------------------------------------------

    @Override
    public String toString() {
        return this.baseDir.getPath();
    }


    /**
     * Options for help method.
     */
    public static final class JkHelpOptions {

        @JkDoc("Output help formatted in XML if true. To be used in conjonction of -silent option to parse the output stream friendly.")
        private boolean xml;

        @JkDoc("Output help in this xml file. If this option is specified, no need to specify help.xml option.")
        private File xmlFile;

        /**
         * Returns true if the help output must be formatted using XML.
         */
        public boolean xml() {
            return xml;
        }

    }

}
