package org.jerkar.tool;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jerkar.api.depmanagement.JkDependencies;
import org.jerkar.api.depmanagement.JkDependencyResolver;
import org.jerkar.api.depmanagement.JkRepo;
import org.jerkar.api.depmanagement.JkRepos;
import org.jerkar.api.depmanagement.JkResolutionParameters;
import org.jerkar.api.depmanagement.JkScope;
import org.jerkar.api.file.JkFileTree;
import org.jerkar.api.file.JkPath;
import org.jerkar.api.java.JkClassLoader;
import org.jerkar.api.java.JkClasspath;
import org.jerkar.api.java.JkJavaCompiler;
import org.jerkar.api.system.JkLog;
import org.jerkar.api.utils.JkUtilsFile;
import org.jerkar.tool.CommandLine.JkPluginSetup;
import org.jerkar.tool.CommandLine.MethodInvocation;

/**
 * Buildable project. This class has the responsability to compile the build classes along to run them.<br/>
 * Build class are expected to lie in [project base dir]/build/spec.<br/>
 * Classes having simple name starting by '_' are not compiled.
 */
class Project {

	private final File projectBaseDir;

	private JkDependencies buildDependencies;

	private JkRepos buildRepos;

	private List<File> subProjects = new LinkedList<File>();

	private JkPath buildPath;

	private final BuildResolver resolver;

	private static final String DOWNLOAD_REPO_URL_OPTION = "downloadRepoUrl";

	private static final String DOWNLOAD_REPO_USER_NAME_OPTION = "dowloadRepoUsername";

	private static final String DOWNLOAD_REPO_PASSWORD_OPTION = "downloadRepoPassword";


	/**
	 * Constructs a project from its base directory and the download repo.
	 * Download repo is used in case the build classes need some dependencies
	 * in order to be compiled/run.
	 */
	public Project(File baseDir) {
		super();
		this.projectBaseDir = JkUtilsFile.canonicalFile(baseDir);
		buildRepos = repos();
		this.buildDependencies = JkDependencies.on();
		this.resolver = new BuildResolver(baseDir);
	}

	private void preCompile() {
		final JavaSourceParser parser = JavaSourceParser.of(this.projectBaseDir, JkFileTree.of(resolver.buildSourceDir).include("**/*.java"));
		this.buildDependencies = parser.dependencies();
		this.buildRepos = parser.importRepos().and(buildRepos);
		this.subProjects = parser.projects();
	}

	private void compile() {
		final LinkedHashSet<File> entries = new LinkedHashSet<File>();
		compile(new HashSet<File>(), entries);
		this.buildPath = JkPath.of(entries);
	}

	private void compile(Set<File> yetCompiledProjects, LinkedHashSet<File> path) {
		if (!this.resolver.hasBuildSource() || yetCompiledProjects.contains(this.projectBaseDir)) {
			return;
		}
		yetCompiledProjects.add(this.projectBaseDir);
		preCompile();
		JkLog.startHeaded("Making build classes for project " + this.projectBaseDir.getName());
		final JkDependencyResolver scriptDepResolver = getBuildDefDependencyResolver();
		final JkPath buildPath = scriptDepResolver.get(JkScope.BUILD);
		path.addAll(buildPath.entries());
		path.addAll(compileDependentProjects(yetCompiledProjects, path).entries());
		this.compileBuild(JkPath.of(path));
		path.add(this.resolver.buildClassDir);
		JkLog.done();
	}

	public <T extends JkBuild> T getBuild(Class<T> baseClass) {
		if (resolver.needCompile()) {
			this.compile();
		}
		return resolver.resolve(baseClass);
	}

	public List<Class<?>> getBuildClasses() {
		if (resolver.needCompile()) {
			this.compile();
		}
		return resolver.resolveBuildClasses();
	}

	/**
	 * Pre-compile and compile build classes (if needed) then execute the build of this project.
	 * 
	 * @param buildClassNameHint The full or simple class name of the build class to execute. It can be <code>null</code>
	 * or empty.
	 */
	public void execute(CommandLine commandLine, String buildClassNameHint) {
		compile();
		JkLog.nextLine();
		final JkClassLoader classLoader = JkClassLoader.current();
		classLoader.addEntries(this.buildPath);
		JkLog.info("Setting build execution classpath to : " + classLoader.childClasspath());
		final JkBuild build = resolver.resolve(buildClassNameHint);
		if (build == null) {
			throw new JkException("Can't find or guess any build class for project hosted in " +  this.projectBaseDir
					+ " .\nAre you sure this directory is a buildable project ?");
		}
		try {
			this.launch(build, commandLine);
		} catch(final RuntimeException e) {
			JkLog.error("Project " + projectBaseDir.getAbsolutePath() + " failed");
			throw e;
		}
	}

	private JkDependencies scriptDependencies() {
		return JkDependencies.builder()
				.on(buildDependencies)
				.onFiles(localBuildPath()).scope(JkScope.BUILD)
				.build();
	}

	private	JkPath localBuildPath() {
		final List<File> extraLibs = new LinkedList<File>();
		final File localJerkarBuild = new File(this.projectBaseDir, JkConstants.BUILD_LIB_DIR);
		if (localJerkarBuild.exists()) {
			extraLibs.addAll(JkFileTree.of(localJerkarBuild).include("**/*.jar").files(false));
		}
		if (JkLocator.libExtDir().exists()) {
			extraLibs.addAll(JkFileTree.of(JkLocator.libExtDir()).include("**/*.jar").files(false));
		}
		return JkPath.of(extraLibs).and(JkClasspath.jerkarJarFile()).withoutDoubloons();
	}

	private JkPath compileDependentProjects(Set<File> yetCompiledProjects, LinkedHashSet<File> pathEntries) {
		JkPath jkPath = JkPath.of();
		for (final File file : this.subProjects) {
			final Project project = new Project(file);
			project.compile(yetCompiledProjects, pathEntries);
			jkPath =jkPath.and(file);
		}
		return jkPath;
	}

	private void compileBuild(JkPath buildPath) {
		baseBuildCompiler().withClasspath(buildPath).compile();
		JkFileTree.of(this.resolver.buildSourceDir).exclude("**/*.java").copyTo(this.resolver.buildClassDir);
	}

	private void launch(JkBuild build, CommandLine commandLine) {
		JkOptions.populateFields(build, commandLine.getMasterBuildOptions());
		build.setScriptDependencyResolver(getBuildDefDependencyResolver());
		build.init();

		// setup plugins
		final Class<JkBuildPlugin> baseClass = JkClassLoader.of(build.getClass()).load(JkBuildPlugin.class.getName());
		final PluginDictionnary<JkBuildPlugin> dictionnary = PluginDictionnary.of(baseClass);

		if (build instanceof JkBuildDependencySupport) {
			final JkBuildDependencySupport depBuild = (JkBuildDependencySupport) build;
			if (!depBuild.multiProjectDependencies().transitiveProjectBuilds().isEmpty()) {
				if (!commandLine.getSubProjectMethods().isEmpty()) {
					JkLog.startHeaded("Executing dependent projects");
					for (final JkBuild subBuild : depBuild.multiProjectDependencies().transitiveProjectBuilds()) {
						configurePluginsAndRun(subBuild, commandLine.getSubProjectMethods(),
								commandLine.getSubProjectPluginSetups(), commandLine.getSubProjectBuildOptions(), dictionnary);

					}
				} else {
					JkLog.startln("Configuring dependent projects");
					for (final JkBuild subBuild : depBuild.multiProjectDependencies().transitiveProjectBuilds()) {
						JkLog.startln("Configuring " + subBuild.baseDir().root().getName());
						configureProject(subBuild,
								commandLine.getSubProjectPluginSetups(), commandLine.getSubProjectBuildOptions(), dictionnary);
						JkLog.done();
					}
				}
				JkLog.done();
			}
		}
		configurePluginsAndRun(build, commandLine.getMasterMethods(),
				commandLine.getMasterPluginSetups(), commandLine.getMasterBuildOptions(), dictionnary);
	}

	private static void configureProject(JkBuild build,
			Collection<JkPluginSetup> pluginSetups, Map<String, String> options,  PluginDictionnary<JkBuildPlugin> dictionnary) {
		JkOptions.populateFields(build);
		JkOptions.populateFields(build, options);
		configureAndActivatePlugins(build, pluginSetups, dictionnary);
	}

	private static void configurePluginsAndRun(JkBuild build, List<MethodInvocation> invokes,
			Collection<JkPluginSetup> pluginSetups, Map<String, String> options,  PluginDictionnary<JkBuildPlugin> dictionnary) {
		JkLog.startHeaded("Executing build for project " + build.baseDir().root().getName());
		JkLog.info("Build class " + build.getClass().getName());
		configureProject(build, pluginSetups, options, dictionnary);
		JkLog.info("Activated plugins : " + build.plugins.getActives());
		final Map<String, String> displayedOptions = JkOptions.toDisplayedMap(OptionInjector.injectedFields(build));
		Main.logProps("Field values", displayedOptions);
		build.execute(toBuildMethods(invokes, dictionnary), null);
		JkLog.done("Build " + build.baseDir().root().getName());
	}



	private static void configureAndActivatePlugins(JkBuild build, Collection<JkPluginSetup> pluginSetups, PluginDictionnary<JkBuildPlugin> dictionnary) {
		for (final JkPluginSetup pluginSetup : pluginSetups) {
			final Class<? extends JkBuildPlugin> pluginClass =
					dictionnary.loadByNameOrFail(pluginSetup.pluginName).pluginClass();
			if (pluginSetup.activated) {
				JkLog.startln("Activating plugin " + pluginClass.getName());
				final Object plugin = build.plugins.addActivated(pluginClass, pluginSetup.options);
				JkLog.done("Activating plugin " + pluginClass.getName() + " with options "
						+ JkOptions.fieldOptionsToString(plugin));
			} else {
				JkLog.startln("Configuring plugin " + pluginClass.getName());
				final Object plugin = build.plugins.addConfigured(pluginClass, pluginSetup.options);
				JkLog.done("Configuring plugin " + pluginClass.getName() + " with options "
						+ JkOptions.fieldOptionsToString(plugin));
			}
		}
	}

	private static List<JkModelMethod> toBuildMethods(Iterable<MethodInvocation> invocations, PluginDictionnary<JkBuildPlugin> dictionnary) {
		final List<JkModelMethod> jkModelMethods = new LinkedList<JkModelMethod>();
		for (final MethodInvocation methodInvokation : invocations) {
			if (methodInvokation.isMethodPlugin()) {
				final Class<? extends JkBuildPlugin> clazz = dictionnary.loadByNameOrFail(methodInvokation.pluginName).pluginClass();
				jkModelMethods.add(JkModelMethod.pluginMethod(clazz, methodInvokation.methodName));
			} else {
				jkModelMethods.add(JkModelMethod.normal(methodInvokation.methodName));
			}
		}
		return jkModelMethods;
	}

	private JkJavaCompiler baseBuildCompiler() {
		final JkFileTree buildSource = JkFileTree.of(resolver.buildSourceDir).include("**/*.java").exclude("**/_*");
		if (!resolver.buildClassDir.exists()) {
			resolver.buildClassDir.mkdirs();
		}
		return JkJavaCompiler.ofOutput(resolver.buildClassDir)
				.andSources(buildSource)
				.failOnError(true);
	}

	private JkDependencyResolver getBuildDefDependencyResolver() {
		final JkDependencies deps = this.scriptDependencies();
		if (deps.containsExternalModule()) {
			return JkDependencyResolver.managed(this.buildRepos, deps, null, JkResolutionParameters.of());
		}
		return JkDependencyResolver.unmanaged(deps);
	}


	@Override
	public String toString() {
		return this.projectBaseDir.getName();
	}

	private static JkRepos repos() {
		final String downloadRepoUrl = JkOptions.get(DOWNLOAD_REPO_URL_OPTION);
		if (downloadRepoUrl == null) {
			return JkRepos.mavenCentral();
		}
		return JkRepos.of(JkRepo.of(JkOptions.get(DOWNLOAD_REPO_URL_OPTION))
				.withOptionalCredentials(JkOptions.get(DOWNLOAD_REPO_USER_NAME_OPTION),
						JkOptions.get(DOWNLOAD_REPO_PASSWORD_OPTION)));
	}

}