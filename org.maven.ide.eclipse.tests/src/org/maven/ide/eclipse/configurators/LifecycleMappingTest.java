
package org.maven.ide.eclipse.configurators;

import java.util.List;

import org.maven.ide.eclipse.internal.project.CustomizableLifecycleMapping;
import org.maven.ide.eclipse.internal.project.GenericLifecycleMapping;
import org.maven.ide.eclipse.internal.project.MojoExecutionProjectConfigurator;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator;
import org.maven.ide.eclipse.project.configurator.ILifecycleMapping;

public class LifecycleMappingTest extends AbstractLifecycleMappingTest {
  public void testGenericMapping() throws Exception {
    IMavenProjectFacade facade = importMavenProject("projects/lifecyclemapping/generic/pom.xml");

    ILifecycleMapping lifecycleMapping = projectConfigurationManager.getLifecycleMapping(facade, monitor);

    assertTrue( lifecycleMapping instanceof GenericLifecycleMapping );
  }

  public void testCustomizableMapping() throws Exception {
    IMavenProjectFacade facade = importMavenProject("projects/lifecyclemapping/customizable/pom.xml");

    ILifecycleMapping lifecycleMapping = projectConfigurationManager.getLifecycleMapping(facade, monitor);

    assertTrue(lifecycleMapping instanceof CustomizableLifecycleMapping);

    List<AbstractProjectConfigurator> configurators = lifecycleMapping.getProjectConfigurators(facade, monitor);
    assertEquals(4, configurators.size());
    assertTrue(configurators.get(3) instanceof MojoExecutionProjectConfigurator);
  }
}
