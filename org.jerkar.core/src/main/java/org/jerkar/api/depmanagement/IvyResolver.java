package org.jerkar.api.depmanagement;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyContext;
import org.apache.ivy.core.cache.ResolutionCacheManager;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.MDArtifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.DownloadStatus;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.IvyNodeCallers.Caller;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.util.url.URLHandlerRegistry;
import org.jerkar.api.depmanagement.JkDependencyNode.ModuleNodeInfo;
import org.jerkar.api.system.JkLocator;
import org.jerkar.api.system.JkLog;
import org.jerkar.api.utils.JkUtilsIterable;
import org.jerkar.api.utils.JkUtilsObject;
import org.jerkar.api.utils.JkUtilsThrowable;

import java.io.File;
import java.util.*;

/**
 * Jerkar users : This class is not part of the public API !!! Please, Use
 * {@link JkDependencyResolver} instead. Ivy wrapper providing high level methods. The
 * API is expressed using Jerkar classes only (mostly free of Ivy classes).
 *
 * @author Jerome Angibaud
 */
final class IvyResolver implements InternalDepResolver {

    private static final Random RANDOM = new Random();

    private static final String[] IVY_24_ALL_CONF = new String[] { "*(public)" };

    private final Ivy ivy;

    private IvyResolver(Ivy ivy) {
        super();
        this.ivy = ivy;
    }

    private static IvyResolver of(IvySettings ivySettings) {
        final Ivy ivy = ivy(ivySettings);
        return new IvyResolver(ivy);
    }

    static Ivy ivy(IvySettings ivySettings) {
        final Ivy ivy = new Ivy();
        ivy.getLoggerEngine().popLogger();
        ivy.getLoggerEngine().setDefaultLogger(new IvyMessageLogger());
        ivy.getLoggerEngine().setShowProgress(JkLog.verbose());
        ivy.getLoggerEngine().clearProblems();
        IvyContext.getContext().setIvy(ivy);
        ivy.setSettings(ivySettings);
        ivy.bind();
        URLHandlerRegistry.setDefault(new IvyFollowRedirectUrlHandler());
        return ivy;
    }

    /**
     * Creates an <code>IvySettings</code> from the specified repositories.
     */
    private static IvySettings ivySettingsOf(JkRepos resolveRepos) {
        final IvySettings ivySettings = new IvySettings();
        IvyTranslations.populateIvySettingsWithRepo(ivySettings, resolveRepos);
        ivySettings.setDefaultCache(JkLocator.jerkarRepositoryCache());
        return ivySettings;
    }

    /**
     * Creates an instance using specified repository for publishing and the
     * specified repositories for resolving.
     */
    public static IvyResolver of(JkRepos resolveRepos) {
        return of(ivySettingsOf(resolveRepos));
    }

    @SuppressWarnings("unchecked")
    @Override
    public JkResolveResult resolve(JkVersionedModule moduleArg, JkDependencies deps,
            JkResolutionParameters parameters, JkVersionProvider versionProvider, JkScope ... resolvedScopes) {

        final JkVersionedModule module;
        if (moduleArg == null) {
            module = anonymousVersionedModule();
        } else {
            module = moduleArg;
        }

        if (parameters == null) {
            parameters = JkResolutionParameters.of();
        }
        if (versionProvider == null) {
            versionProvider = JkVersionProvider.empty();
        }
        final DefaultModuleDescriptor moduleDescriptor = IvyTranslations.toPublicationLessModule(module, deps,
                parameters.defaultMapping(), versionProvider, ivy.getSettings());

        final String[] confs = toConfs(deps.declaredScopes(), resolvedScopes);
        final ResolveOptions resolveOptions = new ResolveOptions();
        resolveOptions.setConfs(confs);
        resolveOptions.setTransitive(true);
        resolveOptions.setOutputReport(JkLog.verbose());
        resolveOptions.setLog(logLevel());
        resolveOptions.setRefresh(parameters.refreshed());
        resolveOptions.setCheckIfChanged(true);
        if (resolvedScopes.length == 0) {   // if no scope, verbose ivy report turns in exception
            resolveOptions.setOutputReport(false);
        }
        final ResolveReport report;
        try {
            report = ivy.resolve(moduleDescriptor, resolveOptions);
        } catch (final Exception e) {
            throw JkUtilsThrowable.unchecked(e);
        }
        final JkResolveResult.JkErrorReport errorReport;
        if (report.hasError()) {
            errorReport = JkResolveResult.JkErrorReport.failure(missingArtifacts(
                    report.getAllArtifactsReports()));
        } else {
            errorReport = JkResolveResult.JkErrorReport.allFine();
        }
        final ArtifactDownloadReport[] artifactDownloadReports = report.getAllArtifactsReports();
        JkResolveResult resolveResult = getResolveConf(artifactDownloadReports, deps,
                    report.getDependencies(), module, errorReport);
        if (moduleArg == null) {
            deleteResolveCache(module);
        }
        return resolveResult;
    }

    private void deleteResolveCache(JkVersionedModule module) {
        final ResolutionCacheManager cacheManager = this.ivy.getSettings().getResolutionCacheManager();
        final ModuleRevisionId moduleRevisionId = IvyTranslations.toModuleRevisionId(module);
        final File propsFile = cacheManager.getResolvedIvyPropertiesInCache(moduleRevisionId);
        propsFile.delete();
        final File xmlFile = cacheManager.getResolvedIvyFileInCache(moduleRevisionId);
        xmlFile.delete();
    }

    private static String logLevel() {
        if (JkLog.silent()) {
            return "quiet";
        }
        if (JkLog.verbose()) {
            return "verbose";
        }
        return "download-only";
    }

    private static JkResolveResult getResolveConf(ArtifactDownloadReport[] artifactDownloadReports,
                                                  JkDependencies deps, List<IvyNode> nodes,
                                                  JkVersionedModule rootVersionedModule,
                                                  JkResolveResult.JkErrorReport errorReport) {

        // Get module dependency files
        final List<JkModuleArtifact> jkArtifacts = new LinkedList<JkModuleArtifact>();
        JkVersionProvider versionProvider = JkVersionProvider.empty();
        for (final ArtifactDownloadReport artifactDownloadReport : artifactDownloadReports) {
            final JkVersionedModule versionedModule = IvyTranslations
                    .toJkVersionedModule(artifactDownloadReport.getArtifact());
            final JkModuleArtifact jkArtifact = JkModuleArtifact.of(versionedModule,
                    artifactDownloadReport.getLocalFile());
            jkArtifacts.add(jkArtifact);

            // Populate version provider
            final JkScopedDependency declaredDep = deps.get(versionedModule.moduleId());
            if (declaredDep != null) {
                final JkModuleDependency module = (JkModuleDependency) declaredDep.dependency();
                if (module.versionRange().isDynamicAndResovable()) {
                    versionProvider = versionProvider.and(module.moduleId(), versionedModule.version());
                }
            }
        }

        // Compute dependency tree
        final JkDependencyNode tree = createTree(nodes, rootVersionedModule);
        return JkResolveResult.of(jkArtifacts, versionProvider, tree, errorReport);
    }

    private static JkVersionedModule anonymousVersionedModule() {
        final String version = Long.toString(RANDOM.nextLong());
        return JkVersionedModule.of(JkModuleId.of("anonymousGroup", "anonymousName"), JkVersion.name(version));
    }

    @Override
    public File get(JkModuleDependency dependency) {
        final ModuleRevisionId moduleRevisionId = IvyTranslations.toModuleRevisionId(dependency.moduleId(),
                dependency.versionRange());
        final boolean isMetadata = "pom".equalsIgnoreCase(dependency.ext());
        final String typeAndExt = JkUtilsObject.firstNonNull(dependency.ext(), "jar");
        final DefaultArtifact artifact;
        if (isMetadata) {
            artifact = new DefaultArtifact(moduleRevisionId, null, dependency.moduleId().name(), typeAndExt,
                    typeAndExt, true);
        } else {
            Map<String, String> extra = new HashMap<String, String>();
            if (dependency.classifier() != null) {
                extra.put("classifier", dependency.classifier());
            }
            artifact = new DefaultArtifact(moduleRevisionId, null, dependency.moduleId().name(), typeAndExt,
                    typeAndExt, extra);
        }
        final ArtifactDownloadReport report = ivy.getResolveEngine().download(artifact, new DownloadOptions());
        return report.getLocalFile();
    }

    private static JkDependencyNode createTree(Iterable<IvyNode> nodes, JkVersionedModule rootVersionedModule) {
        final IvyTreeResolver treeResolver = new IvyTreeResolver(nodes);
        final ModuleNodeInfo moduleNodeInfo = new ModuleNodeInfo(rootVersionedModule.moduleId(),
                JkVersionRange.of(rootVersionedModule.version().name()), new HashSet<JkScope>(), new HashSet<JkScope>(),
                rootVersionedModule.version() );
        return treeResolver.createNode(moduleNodeInfo);
    }

    private static class IvyTreeResolver {

        // parent to children parentChildMap
        private final Map<JkModuleId, List<ModuleNodeInfo>> parentChildMap = new HashMap<JkModuleId, List<ModuleNodeInfo>>();

        IvyTreeResolver(Iterable<IvyNode> nodes) {


            for (final IvyNode node : nodes) {
                final JkVersionedModule nodeModule = toJkVersionedModule(node);
                final JkDependency currentDep = JkModuleDependency.of(nodeModule);

                final Caller[] callers = node.getAllCallers();
                for (final Caller caller : callers) {
                    final DependencyDescriptor dependencyDescriptor = caller.getDependencyDescriptor();
                    final JkVersionedModule parent = IvyTranslations.toJkVersionedModule(caller.getModuleRevisionId());
                    List<ModuleNodeInfo> list = parentChildMap.get(parent.moduleId());
                    if (list == null) {
                        list = new LinkedList<ModuleNodeInfo>();
                        parentChildMap.put(parent.moduleId(), list);
                    }
                    final Set<JkScope> declaredScopes = IvyTranslations.toJkScopes(dependencyDescriptor.getModuleConfigurations());
                    final JkVersionRange versionRange = JkVersionRange.of(dependencyDescriptor
                        .getDynamicConstraintDependencyRevisionId().getRevision());
                    JkModuleId moduleId = JkModuleId.of(node.getId().getOrganisation(), node.getId().getName());
                    JkModuleDependency moduleDependency = JkModuleDependency.of(moduleId, versionRange);
                    final JkVersion resolvedVersion = JkVersion.name(node.getResolvedId().getRevision());
                    final Set<JkScope> rootScopes = IvyTranslations.toJkScopes(node.getRootModuleConfigurations());

                    ModuleNodeInfo moduleNodeInfo  = new ModuleNodeInfo(moduleId, versionRange, declaredScopes,
                            rootScopes, resolvedVersion);
                    if (!containSame(list, moduleId)) {
                        list.add(moduleNodeInfo);
                    }
                }
            }
        }

        private static boolean containSame(List<ModuleNodeInfo> list, JkModuleId moduleId) {
            for (ModuleNodeInfo moduleNodeInfo : list) {
                if (moduleNodeInfo.moduleId().equals(moduleId)) {
                    return true;
                }
            }
            return false;
        }

        JkDependencyNode createNode(ModuleNodeInfo holder) {
            if (parentChildMap.get(holder.moduleId()) == null || holder.isEvicted()) {
                return JkDependencyNode.ofModuleDep(holder, new LinkedList<JkDependencyNode>());
            }

            List<ModuleNodeInfo> moduleNodeInfos = parentChildMap.get(holder.moduleId());
            if (moduleNodeInfos == null) {
                moduleNodeInfos = new LinkedList<ModuleNodeInfo>();
            }
            final List<JkDependencyNode> childNodes = new LinkedList<JkDependencyNode>();
            for (final ModuleNodeInfo moduleNodeInfo : moduleNodeInfos) {
                final JkDependencyNode childNode = createNode(moduleNodeInfo);
                childNodes.add(childNode);
            }
            return JkDependencyNode.ofModuleDep(holder, childNodes);
        }


        private static JkModuleId moduleId(JkScopedDependency scopedDependency) {
            final JkModuleDependency moduleDependency = (JkModuleDependency) scopedDependency.dependency();
            return moduleDependency.moduleId();
        }

    }

    private static JkVersionedModule toJkVersionedModule(IvyNode ivyNode) {
        return IvyTranslations.toJkVersionedModule(ivyNode.getResolvedId());
    }

    private List<JkArtifactDef> missingArtifacts(ArtifactDownloadReport[] artifactDownloadReports) {
        List<JkArtifactDef> result = new LinkedList<JkArtifactDef>();
        for (ArtifactDownloadReport artifactDownloadReport : artifactDownloadReports) {
            if (artifactDownloadReport.getDownloadStatus() == DownloadStatus.FAILED) {
                if (ArtifactDownloadReport.MISSING_ARTIFACT.equals(artifactDownloadReport.getDownloadDetails())) {
                    JkArtifactDef artifactDef = IvyTranslations.toJkArtifactDef((MDArtifact) artifactDownloadReport.getArtifact());
                    result.add(artifactDef);
                }
            }
        }
        return result;
    }

    private String[] toConfs(Set<JkScope> declaredScopes, JkScope ... resolvedScopes) {
        if (resolvedScopes.length == 0) {
            return IVY_24_ALL_CONF;
        }
        Set<String> result = new HashSet<String>();
        for (int i = 0; i < resolvedScopes.length; i++) {
            List<JkScope> scopes = resolvedScopes[i].commonScopes(declaredScopes);
            for (JkScope scope : scopes) {
                result.add(scope.name());
            }
        }
        return JkUtilsIterable.arrayOf(result, String.class);
    }

}
