import org.jerkar.api.depmanagement.JkArtifactId;
import org.jerkar.api.depmanagement.JkDependencySet;
import org.jerkar.api.java.JkJavaVersion;
import org.jerkar.samples.AClassicBuild;
import org.jerkar.tool.JkImportRun;
import org.jerkar.tool.JkInit;
import org.jerkar.tool.builtins.java.JkJavaProjectBuild;

/**
 * Simple build demonstrating of how Jerkar can handle multi-project build.
 * <p>
 * Here, the project depends on the <code>org.jerkar.samples</code> project.
 * More precisely, on the [fat
 * jar](http://stackoverflow.com/questions/19150811/what-is-a-fat-jar) file
 * produced by the <code>AClassicalBuild</code> build.
 * <p>
 * The compilation relies on a fat jar (a jar containing all the dependencies)
 * produced by <code>org.jerkar.samples</code> project. The build produces in
 * turns, produces a fat jar merging the fat jar dependency, the classes of this
 * project and its module dependencies.
 * 
 * @author Jerome Angibaud
 * 
 * @formatter:off
 */
public class FatJarBuild extends JkJavaProjectBuild {
    
    @JkImportRun("../org.jerkar.samples")
    private AClassicBuild sampleBuild;

    @Override
    protected void afterOptionsInjected() {
        project().addDependencies(JkDependencySet.of()
                .and(sampleBuild.project(), JkArtifactId.of("fat", "jar")));
        project().setSourceVersion(JkJavaVersion.V7);
    } 
    
    public static void main(String[] args) {
		JkInit.instanceOf(FatJarBuild.class, "-java#tests.fork").maker().makeAllArtifacts();
	}

   
}