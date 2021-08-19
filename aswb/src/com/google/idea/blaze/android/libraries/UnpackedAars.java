/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.libraries;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact.LocalFileArtifact;
import com.google.idea.blaze.base.command.buildresult.RemoteOutputArtifact;
import com.google.idea.blaze.base.filecache.FileCache;
import com.google.idea.blaze.base.filecache.FileCacheDiffer;
import com.google.idea.blaze.base.model.BlazeLibrary;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.RemoteOutputArtifacts;
import com.google.idea.blaze.base.prefetch.RemoteArtifactPrefetcher;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope.EventType;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.SyncMode;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.libraries.BlazeLibraryCollector;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Manage local aars used by the project. Provides methods to update aars after every sync and path
 * to resource direcotry of every aars.
 */
public class UnpackedAars {
  private static final Logger logger = Logger.getInstance(UnpackedAars.class);

  private final Project project;
  private final AarCache aarCache;

  public static UnpackedAars getInstance(Project project) {
    return ServiceManager.getService(project, UnpackedAars.class);
  }

  public UnpackedAars(Project project) {
    BlazeImportSettings importSettings =
        BlazeImportSettingsManager.getInstance(project).getImportSettings();
    this.project = project;
    this.aarCache = new AarCache(getCacheDir(importSettings));
  }

  @VisibleForTesting
  public File getCacheDir() {
    return this.aarCache.getCacheDir();
  }

  private static File getCacheDir(BlazeImportSettings importSettings) {
    return new File(BlazeDataStorage.getProjectDataDir(importSettings), "aar_libraries");
  }

  void onSync(
      BlazeContext context,
      ProjectViewSet projectViewSet,
      BlazeProjectData projectData,
      @Nullable BlazeProjectData oldProjectData,
      SyncMode syncMode) {
    boolean fullRefresh = syncMode == SyncMode.FULL;
    if (fullRefresh) {
      clearCache();
    }

    // TODO(brendandouglas): add a mechanism for removing missing files for partial syncs
    boolean removeMissingFiles = syncMode == SyncMode.INCREMENTAL;
    refresh(
        context,
        projectViewSet,
        projectData,
        RemoteOutputArtifacts.fromProjectData(oldProjectData),
        removeMissingFiles);
  }

  private void refresh(
      BlazeContext context,
      ProjectViewSet viewSet,
      BlazeProjectData projectData,
      RemoteOutputArtifacts previousOutputs,
      boolean removeMissingFiles) {
    if (!aarCache.createCacheDir()) {
      logger.warn("Could not create unpacked AAR directory: " + getCacheDir());
      return;
    }

    ImmutableMap<String, File> cacheFiles = readFileState();
    ImmutableMap<String, AarAndJar> projectState = getArtifactsToCache(viewSet, projectData);
    ImmutableMap<String, BlazeArtifact> aarOutputs =
        projectState.entrySet().stream()
            .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().aar()));
    try {

      Set<String> updatedKeys =
          FileCacheDiffer.findUpdatedOutputs(aarOutputs, cacheFiles, previousOutputs).keySet();
      Set<BlazeArtifact> artifactsToDownload = new HashSet<>();

      for (String key : updatedKeys) {
        artifactsToDownload.add(projectState.get(key).aar());
        BlazeArtifact jar = projectState.get(key).jar();
        // jar file is introduced as a separate artifact (not jar in aar) which asks to download
        // separately. Only update jar when we decide that aar need to be updated.
        if (jar != null) {
          artifactsToDownload.add(jar);
        }
      }

      // Prefetch all libraries to local before reading and copying content
      ListenableFuture<?> downloadArtifactsFuture =
          RemoteArtifactPrefetcher.getInstance()
              .downloadArtifacts(
                  /* projectName= */ project.getName(),
                  /* outputArtifacts= */ BlazeArtifact.getRemoteArtifacts(artifactsToDownload));

      FutureUtil.waitForFuture(context, downloadArtifactsFuture)
          .timed("FetchAars", EventType.Prefetching)
          .withProgressMessage("Fetching aar files...")
          .run();

      Unpacker.unpack(projectState, updatedKeys, getCacheDir());
      if (!updatedKeys.isEmpty()) {
        context.output(PrintOutput.log(String.format("Copied %d AARs", updatedKeys.size())));
      }

      if (removeMissingFiles) {
        Collection<ListenableFuture<?>> removedFiles =
            aarCache.removeMissingFiles(/* aarsToKeep= */ projectState.keySet());
        Futures.allAsList(removedFiles).get();
        if (!removedFiles.isEmpty()) {
          context.output(PrintOutput.log(String.format("Removed %d AARs", removedFiles.size())));
        }
      }

    } catch (InterruptedException e) {
      context.setCancelled();
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      logger.warn("Unpacked AAR synchronization didn't complete", e);
    } finally {
      // update the in-memory record of which files are cached
      readFileState();
    }
  }

  /** Returns the merged jar derived from an AAR, in the unpacked AAR directory. */
  @Nullable
  public File getClassJar(ArtifactLocationDecoder decoder, AarLibrary library) {
    if (library.libraryArtifact == null) {
      return null;
    }
    BlazeArtifact jar = decoder.resolveOutput(library.libraryArtifact.jarForIntellijLibrary());
    BlazeArtifact aar = decoder.resolveOutput(library.aarArtifact);

    File aarDir = getAarDir(library);
    if (aarDir == null) {
      // if artifact is RemoteOutputArtifact, cacheState is expected to contains cacheKey. So it's
      // unexpected when it runs into this case.
      if (jar instanceof RemoteOutputArtifact) {
        logger.warn(
            String.format(
                "Fail to look up %s from cache state for library [aarArtifact = %s, jar = %s]",
                aarDir, aar, jar));
        logger.debug("Cache state contains the following keys: " + aarCache.getCachedKeys());
      }
      return getFallbackFile(jar);
    }
    return AarDirectoryNameUtils.getJarFile(aarDir);
  }

  /** Returns the res/ directory corresponding to an unpacked AAR file. */
  @Nullable
  public File getResourceDirectory(AarLibrary library) {
    File aarDir = getAarDir(library);
    return aarDir == null ? aarDir : AarDirectoryNameUtils.getResDir(aarDir);
  }

  @Nullable
  public File getAarDir(AarLibrary library) {
    String aarDirName = Unpacker.getAarDirName(library);
    return aarCache.getCachedAarDir(aarDirName);
  }

  /** The file to return if there's no locally cached version. */
  private static File getFallbackFile(BlazeArtifact output) {
    if (output instanceof RemoteOutputArtifact) {
      // TODO(brendandouglas): copy locally on the fly?
      throw new RuntimeException("The AAR cache must be enabled when syncing remotely");
    }
    return ((LocalFileArtifact) output).getFile();
  }

  private void clearCache() {
    aarCache.clearCache();
  }

  static class FileCacheAdapter implements FileCache {
    @Override
    public String getName() {
      return "Unpacked AAR libraries";
    }

    @Override
    public void onSync(
        Project project,
        BlazeContext context,
        ProjectViewSet projectViewSet,
        BlazeProjectData projectData,
        @Nullable BlazeProjectData oldProjectData,
        SyncMode syncMode) {
      getInstance(project).onSync(context, projectViewSet, projectData, oldProjectData, syncMode);
    }

    @Override
    public void refreshFiles(Project project, BlazeContext context) {
      ProjectViewSet viewSet = ProjectViewManager.getInstance(project).getProjectViewSet();
      BlazeProjectData projectData =
          BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
      if (viewSet == null || projectData == null || !projectData.getRemoteOutputs().isEmpty()) {
        // if we have remote artifacts, only refresh during sync
        return;
      }
      getInstance(project)
          .refresh(
              context,
              viewSet,
              projectData,
              projectData.getRemoteOutputs(),
              /* removeMissingFiles= */ false);
    }

    @Override
    public void initialize(Project project) {
      getInstance(project).readFileState();
    }
  }

  private ImmutableMap<String, File> readFileState() {
    return aarCache.readFileState();
  }

  /**
   * Returns a map from cache key to {@link AarAndJar}, for all the artifacts which should be
   * cached.
   */
  private static ImmutableMap<String, AarAndJar> getArtifactsToCache(
      ProjectViewSet projectViewSet, BlazeProjectData projectData) {
    Collection<BlazeLibrary> libraries =
        BlazeLibraryCollector.getLibraries(projectViewSet, projectData);
    List<AarLibrary> aarLibraries =
        libraries.stream()
            .filter(library -> library instanceof AarLibrary)
            .map(library -> (AarLibrary) library)
            .collect(Collectors.toList());

    ArtifactLocationDecoder decoder = projectData.getArtifactLocationDecoder();
    Map<String, AarAndJar> outputs = new HashMap<>();
    for (AarLibrary library : aarLibraries) {
      BlazeArtifact aar = decoder.resolveOutput(library.aarArtifact);
      BlazeArtifact jar =
          library.libraryArtifact != null
              ? decoder.resolveOutput(library.libraryArtifact.jarForIntellijLibrary())
              : null;
      outputs.put(
          AarDirectoryNameUtils.getAarDirName(aar),
          AarAndJar.create(aar, jar, library.key.toString()));
    }
    return ImmutableMap.copyOf(outputs);
  }
}
