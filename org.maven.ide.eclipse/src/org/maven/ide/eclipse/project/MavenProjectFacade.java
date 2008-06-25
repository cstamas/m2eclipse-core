/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.project;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;

import org.codehaus.plexus.util.StringUtils;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.embedder.MavenEmbedderException;
import org.apache.maven.model.Build;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.internal.project.MavenProjectManagerImpl;


/**
 * MavenProjectFacade
 * 
 * getAllXXX methods consider nested modules of this project, if any. 
 * getXXX methods work on this project only.
 */
public class MavenProjectFacade {
  private final MavenProjectManagerImpl manager;

  private final IFile pom;

  private final MavenProject mavenProject;

  private ResolverConfiguration resolverConfiguration;

  private final long[] timestamp = new long[MavenProjectManagerImpl.METADATA_PATH.length + 1];

  public MavenProjectFacade(MavenProjectManagerImpl manager, IFile pom, MavenProject mavenProject,
      ResolverConfiguration resolverConfiguration) {
    this.manager = manager;
    this.pom = pom;
    this.mavenProject = mavenProject;
    this.resolverConfiguration = resolverConfiguration;
    updateTimestamp();
  }

  /**
   * Returns project relative paths of resource directories
   */
  public IPath[] getResourceLocations() {
    return MavenProjectUtils.getResourceLocations(getProject(), mavenProject.getResources());
  }

  /**
   * Returns project relative paths of test resource directories
   */
  public IPath[] getTestResourceLocations() {
    return MavenProjectUtils.getResourceLocations(getProject(), mavenProject.getTestResources());
  }

  public IPath[] getCompileSourceLocations() {
    return MavenProjectUtils.getSourceLocations(getProject(), mavenProject.getCompileSourceRoots());
  }

  public IPath[] getTestCompileSourceLocations() {
    return MavenProjectUtils.getSourceLocations(getProject(),mavenProject.getTestCompileSourceRoots());
  }

  /**
   * Returns project resource for given filesystem location or null the location is outside of project.
   * 
   * @param resourceLocation absolute filesystem location
   * @return IPath the full, absolute workspace path resourceLocation
   */
  public IPath getProjectRelativePath(String resourceLocation) {
    return MavenProjectUtils.getProjectRelativePath(getProject(), resourceLocation);
  }

  /**
   * Filters resources of this project. Does not recurse into nested modules.
   */
  public void filterResources(IProgressMonitor monitor) throws CoreException {
    try {
      String goalsStr = resolverConfiguration.getResourceFilteringGoals();
      List<String> goals = Arrays.asList(StringUtils.split(goalsStr));
      manager.execute(this, goals, false, monitor);
      refreshBuildDirectory(monitor); // XXX only need to refresh classes and test-classes
    } catch(MavenEmbedderException ex) {
      throw new CoreException(new Status(IStatus.ERROR, MavenPlugin.PLUGIN_ID, -1, ex.getMessage(), ex));
    }
  }

  /**
   * Returns the full, absolute path of this project maven build output directory relative to the workspace or null if
   * maven build output directory cannot be determined or outside of the workspace.
   */
  public IPath getOutputLocation() {
    IPath path = getProjectRelativePath(mavenProject.getBuild().getOutputDirectory());
    return path != null ? getProject().getFullPath().append(path) : null;
  }

  /**
   * Returns the full, absolute path of this project maven build test output directory relative to the workspace or null
   * if maven build output directory cannot be determined or outside of the workspace.
   */
  public IPath getTestOutputLocation() {
    IPath path = getProjectRelativePath(mavenProject.getBuild().getTestOutputDirectory());
    return path != null ? getProject().getFullPath().append(path) : null;
  }

  public IPath getFullPath() {
    return getProject().getFullPath();
  }

  public MavenProject getMavenProject() {
    return mavenProject;
  }

  public Set<Artifact> getTestArtifacts() {
    Set<Artifact> result = new LinkedHashSet<Artifact>();
    addExternalArtifacts(result, getMavenProjectTestArtifacts());
    return result;
  }

  private void addExternalArtifacts(Set<Artifact> result, List<Artifact> artifacts) {
    for(Artifact artifact : artifacts) {
      if(getFullPath(artifact.getFile()) == null) {
        result.add(artifact);
      }
    }
  }

  public Set<Artifact> getRuntimeArtifacts() {
    Set<Artifact> result = new LinkedHashSet<Artifact>();
    addExternalArtifacts(result, getMavenProjectRuntimeArtifacts());
    return result;
  }

  public String getPackaging() {
    return mavenProject.getPackaging();
  }

  public IProject getProject() {
    return pom.getProject();
  }

  public IFile getPom() {
    return pom;
  }

  /**
   * Returns the full, absolute path of the given file relative to the workspace. Returns null if the file does not
   * exist or is not a member of this project.
   */
  public IPath getFullPath(File file) {
    return MavenProjectUtils.getFullPath(getProject(), file);
  }

  /**
   * Visits trough Maven project artifacts and modules
   * 
   * @param visitor a project visitor used to visit Maven project
   * @param flags flags to specify visiting behavior. See {@link IMavenProjectVisitor#LOAD},
   *          {@link IMavenProjectVisitor#NESTED_MODULES}.
   */
  public void accept(IMavenProjectVisitor visitor, int flags) throws CoreException {
    if(visitor.visit(this)) {
      for(Artifact artifact : getMavenProjectArtifacts()) {
        visitor.visit(this, artifact);
      }

      if((flags & IMavenProjectVisitor.FORCE_MODULES) > 0 //
          || ((flags & IMavenProjectVisitor.NESTED_MODULES) > 0 //
              && resolverConfiguration.shouldIncludeModules())) {
        IFile pom = getPom();
        for(String moduleName : getMavenProjectModules()) {
          IFile modulePom = pom.getParent().getFile(new Path(moduleName).append(MavenPlugin.POM_FILE_NAME));
          MavenProjectFacade moduleFacade = manager.create(modulePom, false, null);
          if(moduleFacade == null && ((flags & IMavenProjectVisitor.LOAD) > 0)) {
            moduleFacade = manager.create(modulePom, true, new NullProgressMonitor());
          }
          if(moduleFacade != null) {
            moduleFacade.accept(visitor, flags);
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public List<String> getMavenProjectModules() {
    return mavenProject.getModules();
  }
  
  @SuppressWarnings("unchecked")
  public Set<Artifact> getMavenProjectArtifacts() {
    return mavenProject.getArtifacts();
  }

  @SuppressWarnings("unchecked")
  public List<Artifact> getMavenProjectRuntimeArtifacts() {
    return mavenProject.getRuntimeArtifacts();
  }

  @SuppressWarnings("unchecked")
  public List<Artifact> getMavenProjectTestArtifacts() {
    return mavenProject.getTestArtifacts();
  }
  
  @SuppressWarnings("unchecked")
  public List<Resource> getMavenProjectBuildResources() {
    return mavenProject.getBuild().getResources();
  }
  
  @SuppressWarnings("unchecked")
  public List<Resource> getMavenProjectBuildTestResources() {
    return mavenProject.getBuild().getTestResources();
  }
  
  public ResolverConfiguration getResolverConfiguration() {
    return resolverConfiguration;
  }

  public void setResolverConfiguration(ResolverConfiguration configuration) {
    resolverConfiguration = configuration;
  }

  /**
   * @return true if maven project needs to be re-read from disk  
   */
  public boolean isStale() {
    IPath[] metadata = MavenProjectManagerImpl.METADATA_PATH;
    IProject project = getProject();
    for (int i = 0; i < metadata.length; i++) {
      IFile file = project.getFile(metadata[i]);
      if (timestamp[i] != file.getLocalTimeStamp()) {
        return true;
      }
    }
    return timestamp[timestamp.length - 1] != pom.getLocalTimeStamp();
  }

  private void updateTimestamp() {
    IPath[] metadata = MavenProjectManagerImpl.METADATA_PATH;
    IProject project = getProject();
    for (int i = 0; i < metadata.length; i++) {
      IFile file = project.getFile(metadata[i]);
      timestamp[i] = file.getLocalTimeStamp(); 
    }
    timestamp[timestamp.length - 1] = pom.getLocalTimeStamp();
  }

  /**
   * Executes specified maven goals. 
   * 
   * Recurses into nested modules dependending on resolver configuration.
   */
  public void execute(List<String> goals, IProgressMonitor monitor) throws CoreException {
    try {
      manager.execute(this, goals, true, monitor);
      refreshBuildDirectory(monitor);
    } catch(MavenEmbedderException ex) {
      throw new CoreException(new Status(IStatus.ERROR, MavenPlugin.PLUGIN_ID, -1, ex.getMessage(), ex));
    }
  }

  private void refreshBuildDirectory(final IProgressMonitor monitor) throws CoreException {
    accept(new IMavenProjectVisitor() {
      public boolean visit(MavenProjectFacade projectFacade) throws CoreException {
        Build build = projectFacade.getMavenProject().getBuild();
        refreshFolder(projectFacade.getProjectRelativePath(build.getDirectory()), monitor);
        return true; // keep visiting
      }
      public void visit(MavenProjectFacade projectFacade, Artifact artifact) {
      }
    }, IMavenProjectVisitor.NESTED_MODULES);
  }

  void refreshFolder(IPath path, IProgressMonitor monitor) throws CoreException {
    IFolder folder = getProject().getFolder(path);
    if (folder != null) {
      folder.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }
  }

}
