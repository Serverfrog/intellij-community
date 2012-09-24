package org.jetbrains.jps.incremental.artifacts.instructions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.ProjectPaths;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.JpsProject;

/**
 * @author nik
 */
public interface ArtifactInstructionsBuilderContext {

  @NotNull
  ProjectPaths getProjectPaths();

  JpsProject getJpsProject();

  JpsModel getJpsModel();
}
