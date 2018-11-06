package org.jerkar.integrationtest;

import org.jerkar.api.depmanagement.*;
import org.junit.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

import static org.jerkar.api.depmanagement.JkJavaDepScopes.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by angibaudj on 19-06-17.
 */
public class MergeFileDepIT {

    @Test
    public void treeIsCorrectAfterFileDepInsert() throws URISyntaxException {
        JkVersionedModule holder = JkVersionedModule.of("mygroup:myname:myversion");
        Path dep0File = Paths.get(MergeFileDepIT.class.getResource("dep0").toURI());
        Path dep1File = Paths.get(MergeFileDepIT.class.getResource( "dep1").toURI());
        Path dep2File = Paths.get(MergeFileDepIT.class.getResource( "dep2").toURI());
        JkDependencySet deps = JkDependencySet.of()
                .andFile(dep0File, TEST)
                .and("org.springframework.boot:spring-boot-starter-web:1.5.3.RELEASE", COMPILE_AND_RUNTIME)
                .andFile(dep1File, TEST)
                .and("com.github.briandilley.jsonrpc4j:jsonrpc4j:1.5.0", COMPILE)
                .andFile(dep2File, COMPILE);
        JkDependencyResolver resolver = JkDependencyResolver.of(JkRepo.ofMavenCentral().toSet())
                .withParams(JkResolutionParameters.of(DEFAULT_SCOPE_MAPPING))
                .withModuleHolder(holder);
        JkDependencyNode tree = resolver.resolve(deps).getDependencyTree();

        System.out.println(tree.toStringTree());

        JkDependencyNode.JkModuleNodeInfo root = tree.getModuleInfo();
        assertTrue(root.getDeclaredScopes().isEmpty());
        assertEquals(holder.getModuleId(), tree.getModuleInfo().getModuleId());
        assertEquals(5, tree.getChildren().size());

        JkDependencyNode file0Node = tree.getChildren().get(0);
        List<Path> expected = new LinkedList<>();
        expected.add(dep0File);
        assertEquals(expected, file0Node.getAllResolvedFiles());

        JkDependencyNode starterwebNode = tree.getChildren().get(1);
        assertEquals(JkModuleId.of("org.springframework.boot:spring-boot-starter-web"), starterwebNode.getModuleInfo().getModuleId());

        JkDependencyNode file1Node = tree.getChildren().get(2);
        List<Path> expected1 = new LinkedList<>();
        expected1.add(dep1File);
        assertEquals(expected1, file1Node.getAllResolvedFiles());

        JkDependencyNode jsonRpcNode = tree.getChildren().get(3);
        assertEquals(JkModuleId.of("com.github.briandilley.jsonrpc4j:jsonrpc4j"), jsonRpcNode.getModuleInfo().getModuleId());

        JkDependencyNode file2Node = tree.getChildren().get(4);
        List<Path> expected2 = new LinkedList<>();
        expected2.add(dep2File);
        assertEquals(expected2, file2Node.getAllResolvedFiles());

        // Now check that file dependencies with Test Scope are not present in compile

        tree = resolver.resolve(deps, COMPILE).getDependencyTree();
        System.out.println(tree.toStringTree());

        root = tree.getModuleInfo();
        assertTrue(root.getDeclaredScopes().isEmpty());
        assertEquals(holder.getModuleId(), tree.getModuleInfo().getModuleId());
        assertEquals(3, tree.getChildren().size());


    }

    @Test
    public void flattenOnlyFileDeps() throws URISyntaxException {
        Path dep0File = Paths.get(MergeFileDepIT.class.getResource("dep0").toURI());
        Path dep1File = Paths.get(MergeFileDepIT.class.getResource("dep1").toURI());
        JkDependencySet deps = JkDependencySet.of()
                .andFile(dep0File, TEST)
                .andFile(dep1File, TEST);
        JkDependencyResolver resolver = JkDependencyResolver.of();
        JkDependencyNode tree = resolver.resolve(deps).getDependencyTree();
        assertEquals(2, tree.toFlattenList().size());
        resolver = JkDependencyResolver.of(JkRepo.ofMavenCentral().toSet());
        assertEquals(2, resolver.resolve(deps).getDependencyTree().toFlattenList().size());

    }


}
