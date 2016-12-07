/*
 * Copyright (c) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.moe.client.dvcs.git;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.devtools.moe.client.CommandRunner;
import com.google.devtools.moe.client.CommandRunner.CommandException;
import com.google.devtools.moe.client.FileSystem;
import com.google.devtools.moe.client.FileSystem.Lifetime;
import com.google.devtools.moe.client.Lifetimes;
import com.google.devtools.moe.client.MoeProblem;
import com.google.devtools.moe.client.codebase.LocalWorkspace;
import com.google.devtools.moe.client.project.RepositoryConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Git implementation of {@link LocalWorkspace}, i.e. a 'git clone' to local disk.
 */
public class GitClonedRepository implements LocalWorkspace {

  /**
   * A prefix for branches MOE creates to write migrated changes. For example, if there have been
   * revisions in a to-repository since an equivalence revision, MOE won't try to merge or rebase
   * those changes -- instead, it will create a branch with this prefix from the equivalence
   * revision.
   */
  static final String MOE_MIGRATIONS_BRANCH_PREFIX = "moe_writing_branch_from_";

  private final CommandRunner cmd;
  private final FileSystem filesystem;
  private final String repositoryName;
  private final RepositoryConfig repositoryConfig;
  /**
   * The location to clone from. If snapshotting a locally modified Writer, this will _not_ be
   * the same as repositoryConfig.getUrl(). Otherwise, it will.
   */
  private final String repositoryUrl;

  private File localCloneTempDir;
  private boolean clonedLocally;
  /** The revision of this clone, a Git hash ID */
  private String revId;

  GitClonedRepository(
      CommandRunner cmd,
      FileSystem filesystem,
      String repositoryName,
      RepositoryConfig repositoryConfig) {
    this(cmd, filesystem, repositoryName, repositoryConfig, repositoryConfig.getUrl());
  }

  GitClonedRepository(
      CommandRunner cmd,
      FileSystem filesystem,
      String repositoryName,
      RepositoryConfig repositoryConfig,
      String repositoryUrl) {
    this.cmd = cmd;
    this.filesystem = filesystem;
    this.repositoryName = repositoryName;
    this.repositoryConfig = repositoryConfig;
    this.repositoryUrl = repositoryUrl;
    this.clonedLocally = false;
  }

  @Override
  public String getRepositoryName() {
    return repositoryName;
  }

  @Override
  public RepositoryConfig getConfig() {
    return repositoryConfig;
  }

  @Override
  public File getLocalTempDir() {
    Preconditions.checkState(clonedLocally);
    Preconditions.checkNotNull(localCloneTempDir);
    return localCloneTempDir;
  }

  private void initLocal(File cloneTempDir) throws CommandException, IOException {
    GitRepositoryFactory.runGitCommand(
        ImmutableList.of("init", cloneTempDir.getAbsolutePath()), "");
    GitRepositoryFactory.runGitCommand(
        ImmutableList.of("remote", "add", "origin", repositoryUrl), cloneTempDir.getAbsolutePath());
    if (!repositoryConfig.getCheckoutPaths().isEmpty()) {
      GitRepositoryFactory.runGitCommand(
          ImmutableList.of("config", "core.sparseCheckout", "true"),
          cloneTempDir.getAbsolutePath());
      filesystem.write(
          String.join("\n", repositoryConfig.getCheckoutPaths()) + "\n",
          Paths.get(cloneTempDir.getAbsolutePath(), ".git", "info", "sparse-checkout").toFile());
    }
  }

  @Override
  public void cloneLocallyAtHead(Lifetime cloneLifetime) {
    Preconditions.checkState(!clonedLocally);

    Optional<String> branchName = repositoryConfig.getBranch();
    String tempDirName = branchName.isPresent()
        ? "git_clone_" + repositoryName + "_" + branchName.get() + "_"
        : "git_clone_" + repositoryName + "_";
    localCloneTempDir = filesystem.getTemporaryDirectory(tempDirName, cloneLifetime);

    try {
      initLocal(localCloneTempDir);
      ImmutableList.Builder<String> pullArgs = ImmutableList.builder();
      pullArgs.add("pull");
      if (repositoryConfig.shallowCheckout()) {
        pullArgs.add("--depth=1");
      }
      pullArgs.add("origin", branchName.or("master"));
      GitRepositoryFactory.runGitCommand(pullArgs.build(), localCloneTempDir.getAbsolutePath());
      clonedLocally = true;
      this.revId = "HEAD";
    } catch (CommandException e) {
      throw new MoeProblem(
          e, "Could not clone from git repo at " + repositoryUrl + ": " + e.stderr);
    } catch (IOException e) {
      throw new MoeProblem(
          e, "Could not clone from git repo at " + repositoryUrl + ": " + e.getMessage());
    }
  }

  @Override
  public void updateToRevision(String revId) {
    Preconditions.checkState(clonedLocally);
    Preconditions.checkState("HEAD".equals(this.revId));
    try {
      String headHash = runGitCommand("rev-parse", "HEAD").trim();
      // If we are updating to a revision other than the branch's head, branch from that revision.
      // Otherwise, no update/checkout is necessary since we are already at the desired revId,
      // branch head.
      if (!headHash.equals(revId)) {
        if (repositoryConfig.shallowCheckout()) {
          // Unshallow the repository to enable checking out given revId.
          runGitCommand("fetch", "--unshallow");
        }
        runGitCommand("checkout", revId, "-b", MOE_MIGRATIONS_BRANCH_PREFIX + revId);
      }
      this.revId = revId;
    } catch (CommandException e) {
      throw new MoeProblem(
          e, "Could not update git repo at " + localCloneTempDir + ": " + e.stderr);
    }
  }

  @Override
  public File archiveAtRevision(String revId) {
    Preconditions.checkState(clonedLocally);
    if (Strings.isNullOrEmpty(revId)) {
      revId = "HEAD";
    }
    File archiveLocation =
        filesystem.getTemporaryDirectory(
            String.format("git_archive_%s_%s_", repositoryName, revId), Lifetimes.currentTask());
    try {
      filesystem.makeDirs(archiveLocation);
      if (repositoryConfig.getCheckoutPaths().isEmpty()) {
        // Using this just to get a filename.
        String tarballPath =
            filesystem
                .getTemporaryDirectory(
                    String.format("git_tarball_%s_%s.tar.", repositoryName, revId),
                    Lifetimes.currentTask())
                .getAbsolutePath();

        // Git doesn't support archiving to a directory: it only supports
        // archiving to a tar.  The fastest way to do this would be to pipe the
        // output directly into tar, however, there's no option for that using
        // the classes we have. (michaelpb)
        runGitCommand("archive", "--format=tar", "--output=" + tarballPath, revId);

        // Untar the tarball we just made
        cmd.runCommand(
            "tar",
            ImmutableList.of("xf", tarballPath, "-C", archiveLocation.getAbsolutePath()),
            "");
      } else {
        initLocal(archiveLocation);
        ImmutableList.Builder<String> pullArgs = ImmutableList.builder();
        pullArgs.add("pull");
        if (repositoryConfig.shallowCheckout()) {
          pullArgs.add("--depth=1");
        }
        pullArgs.add("origin", revId);
        GitRepositoryFactory.runGitCommand(pullArgs.build(), archiveLocation.getAbsolutePath());
        GitRepositoryFactory.runGitCommand(
            ImmutableList.of("checkout", revId), archiveLocation.getAbsolutePath());
        // Remove git tracking.
        filesystem.deleteRecursively(Paths.get(archiveLocation.getAbsolutePath(), ".git").toFile());
      }
    } catch (CommandException e) {
      throw new MoeProblem(e,
          "Could not archive git clone at "
              + localCloneTempDir.getAbsolutePath());
    } catch (IOException e) {
      throw new MoeProblem(e,
          "IOException archiving clone at "
              + localCloneTempDir.getAbsolutePath()
              + " to revision "
              + revId);
    }
    return archiveLocation;
  }

  /**
   * Runs a git command with the given arguments, in this cloned repository's directory.
   *
   * @param args a list of arguments for git
   * @return a string containing the STDOUT result
   */
  String runGitCommand(String... args) throws CommandException {
    return cmd.runCommand(
        "git",
        ImmutableList.copyOf(args),
        getLocalTempDir().getAbsolutePath() /*workingDirectory*/);
  }
}