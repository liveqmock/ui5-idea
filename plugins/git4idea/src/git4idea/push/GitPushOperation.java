/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.push;

import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.push.PushSpec;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandlerListener;
import git4idea.commands.GitStandardProgressAnalyzer;
import git4idea.config.GitVcsSettings;
import git4idea.config.UpdateMethod;
import git4idea.history.GitHistoryUtils;
import git4idea.merge.MergeChangeCollector;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.settings.GitPushSettings;
import git4idea.update.GitUpdateProcess;
import git4idea.update.GitUpdateResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Executes git push operation:
 * <ul>
 *   <li>Calls push for the given repositories with given parameters;</li>
 *   <li>Collects results;</li>
 *   <li>If push is rejected, proposes to update via merge or rebase;</li>
 *   <li>Shows a notification about push result</li>
 * </ul>
 */
public class GitPushOperation {

  private static final Logger LOG = Logger.getInstance(GitPushOperation.class);
  private static final int MAX_PUSH_ATTEMPTS = 10;

  private final Project myProject;
  private final Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> myPushSpecs;
  @Nullable private final GitPushTagMode myTagMode;
  private final boolean myForce;
  private final Git myGit;
  private final ProgressIndicator myProgressIndicator;
  private final GitVcsSettings mySettings;
  private final GitPushSettings myPushSettings;
  private final GitPlatformFacade myPlatformFacade;
  private final GitRepositoryManager myRepositoryManager;

  public GitPushOperation(@NotNull Project project,
                          @NotNull Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> pushSpecs,
                          @Nullable GitPushTagMode tagMode,
                          boolean force) {
    myProject = project;
    myPushSpecs = pushSpecs;
    myTagMode = tagMode;
    myForce = force;
    myGit = ServiceManager.getService(Git.class);
    myProgressIndicator = ObjectUtils.notNull(ProgressManager.getInstance().getProgressIndicator(), new EmptyProgressIndicator());
    mySettings = GitVcsSettings.getInstance(myProject);
    myPushSettings = GitPushSettings.getInstance(myProject);
    myPlatformFacade = ServiceManager.getService(project, GitPlatformFacade.class);
    myRepositoryManager = ServiceManager.getService(myProject, GitRepositoryManager.class);

    Map<GitRepository, GitRevisionNumber> currentHeads = ContainerUtil.newHashMap();
    for (GitRepository repository : pushSpecs.keySet()) {
      repository.update();
      String head = repository.getCurrentRevision();
      if (head == null) {
        LOG.error("This repository has no commits");
      }
      else {
        currentHeads.put(repository, new GitRevisionNumber(head));
      }
    }
  }

  @NotNull
  public GitPushResult execute() {
    PushUpdateSettings updateSettings = readPushUpdateSettings();
    Label beforePushLabel = null;
    Label afterPushLabel = null;
    Map<GitRepository, String> preUpdatePositions = updateRootInfoAndRememberPositions();

    final Map<GitRepository, GitPushRepoResult> results = ContainerUtil.newHashMap();
    Map<GitRepository, GitUpdateResult> updatedRoots = ContainerUtil.newHashMap();

    try {
      Collection<GitRepository> remainingRoots = myPushSpecs.keySet();
      for (int pushAttempt = 0;
           pushAttempt < MAX_PUSH_ATTEMPTS && !remainingRoots.isEmpty();
           pushAttempt++, remainingRoots = getRejectedAndNotPushed(results)) {
        Map<GitRepository, GitPushRepoResult> resultMap = push(remainingRoots);
        results.putAll(resultMap);

        GroupedPushResult result = GroupedPushResult.group(resultMap);

        // stop on first error
        if (!result.errors.isEmpty()) {
          break;
        }

        // propose to update if rejected
        if (!result.rejected.isEmpty()) {
          boolean shouldUpdate = true;
          if (pushingToNotTrackedBranch(result.rejected)) {
            shouldUpdate = false;
          }
          else if (pushAttempt == 0 && !mySettings.autoUpdateIfPushRejected()) {
            updateSettings = showDialogAndGetExitCode(result.rejected.keySet(), updateSettings);
            if (updateSettings != null) {
              savePushUpdateSettings(updateSettings);
            }
            else {
              shouldUpdate = false;
            }
          }

          if (!shouldUpdate) {
            break;
          }

          if (beforePushLabel == null) { // put the label only before the very first update
            beforePushLabel = LocalHistory.getInstance().putSystemLabel(myProject, "Before push");
          }
          Collection<GitRepository> rootsToUpdate = updateSettings.shouldUpdateAllRoots() ?
                                                    myRepositoryManager.getRepositories() :
                                                    result.rejected.keySet();
          GitUpdateResult updateResult = update(rootsToUpdate, updateSettings.getUpdateMethod());
          for (GitRepository repository : rootsToUpdate) {
            updatedRoots.put(repository, updateResult); // TODO update result in GitUpdateProcess is a single for several roots
          }
          if (!updateResult.isSuccess() ||
              updateResult == GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS || updateResult == GitUpdateResult.INCOMPLETE) {
            break;
          }
        }
      }
    }
    finally {
      if (beforePushLabel != null) {
        afterPushLabel = LocalHistory.getInstance().putSystemLabel(myProject, "After push");
      }
      for (GitRepository repository : myPushSpecs.keySet()) {
        repository.update();
      }
    }
    return prepareCombinedResult(results, updatedRoots, preUpdatePositions, beforePushLabel, afterPushLabel);
  }

  private static boolean pushingToNotTrackedBranch(@NotNull Map<GitRepository, GitPushRepoResult> rejected) {
    return ContainerUtil.exists(rejected.entrySet(), new Condition<Map.Entry<GitRepository, GitPushRepoResult>>() {
      @Override
      public boolean value(Map.Entry<GitRepository, GitPushRepoResult> entry) {
        GitRepository repository = entry.getKey();
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        assert currentBranch != null;
        GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, currentBranch);
        return trackInfo == null || !trackInfo.getRemoteBranch().getFullName().equals(entry.getValue().getTargetBranch());
      }
    });
  }

  @NotNull
  private static List<GitRepository> getRejectedAndNotPushed(@NotNull final Map<GitRepository, GitPushRepoResult> results) {
    return ContainerUtil.filter(results.keySet(), new Condition<GitRepository>() {
      @Override
      public boolean value(GitRepository repository) {
        return results.get(repository).getType() == GitPushRepoResult.Type.REJECTED ||
               results.get(repository).getType() == GitPushRepoResult.Type.NOT_PUSHED;
      }
    });
  }

  @NotNull
  private Map<GitRepository, String> updateRootInfoAndRememberPositions() {
    return ContainerUtil.map2Map(myPushSpecs.keySet(),
      new Function<GitRepository, Pair<GitRepository, String>>() {
       @Override
       public Pair<GitRepository, String> fun(GitRepository repository) {
         repository.update();
         return Pair.create(repository, repository.getCurrentRevision());
       }
      });
  }

  private GitPushResult prepareCombinedResult(final Map<GitRepository, GitPushRepoResult> allRoots,
                                                 final Map<GitRepository, GitUpdateResult> updatedRoots,
                                                 final Map<GitRepository, String> preUpdatePositions,
                                                 Label beforeUpdateLabel,
                                                 Label afterUpdateLabel) {
    Map<GitRepository, GitPushRepoResult> results = ContainerUtil.newHashMap();
    UpdatedFiles updatedFiles = UpdatedFiles.create();
    for (Map.Entry<GitRepository, GitPushRepoResult> entry : allRoots.entrySet()) {
      GitRepository repository = entry.getKey();
      GitPushRepoResult simpleResult = entry.getValue();
      GitUpdateResult updateResult = updatedRoots.get(repository);
      if (updateResult == null) {
        results.put(repository, simpleResult);
      }
      else {
        collectUpdatedFiles(updatedFiles, repository, preUpdatePositions.get(repository));
        results.put(repository, GitPushRepoResult.addUpdateResult(simpleResult, updateResult));
      }
    }
    return new GitPushResult(results, updatedFiles, beforeUpdateLabel, afterUpdateLabel);
  }

  @NotNull
  private Map<GitRepository, GitPushRepoResult> push(@NotNull Collection<GitRepository> repositories) {
    Map<GitRepository, GitPushRepoResult> results = ContainerUtil.newLinkedHashMap();
    for (GitRepository repository : repositories) {
      PushSpec<GitPushSource, GitPushTarget> spec = myPushSpecs.get(repository);
      Pair<List<GitPushNativeResult>, String> resultOrError = doPush(repository, spec);
      LOG.debug("Pushed to " + DvcsUtil.getShortRepositoryName(repository) + ": " + resultOrError);

      GitLocalBranch source = spec.getSource().getBranch();
      GitPushTarget target = spec.getTarget();
      GitPushRepoResult repoResult;
      if (resultOrError.second != null) {
        repoResult = GitPushRepoResult.error(source, target.getBranch(), resultOrError.second);
      }
      else {
        List<GitPushNativeResult> result = resultOrError.first;
        final GitPushNativeResult branchResult = getBranchResult(result);
        if (branchResult == null) {
          LOG.error("No result for branch among: [" + result + "]");
          continue;
        }
        List<GitPushNativeResult> tagResults = ContainerUtil.filter(result, new Condition<GitPushNativeResult>() {
          @Override
          public boolean value(GitPushNativeResult result) {
            return !result.equals(branchResult) &&
                   (result.getType() == GitPushNativeResult.Type.NEW_REF || result.getType() == GitPushNativeResult.Type.FORCED_UPDATE);
          }
        });
        int commits = collectNumberOfPushedCommits(repository.getRoot(), branchResult);
        repoResult = GitPushRepoResult.convertFromNative(branchResult, tagResults, commits, source, target.getBranch());
      }

      LOG.debug("Converted result: " + repoResult);
      results.put(repository, repoResult);
    }

    // fill other not-processed repositories as not-pushed
    for (GitRepository repository : repositories) {
      if (!results.containsKey(repository)) {
        PushSpec<GitPushSource, GitPushTarget> spec = myPushSpecs.get(repository);
        results.put(repository, GitPushRepoResult.notPushed(spec.getSource().getBranch(), spec.getTarget().getBranch()));
      }
    }
    return results;
  }

  @Nullable
  private static GitPushNativeResult getBranchResult(@NotNull List<GitPushNativeResult> results) {
    return ContainerUtil.find(results, new Condition<GitPushNativeResult>() {
      @Override
      public boolean value(GitPushNativeResult result) {
        return result.getSourceRef().startsWith("refs/heads/");
      }
    });
  }

  @NotNull
  private static <T> List<T> without(@NotNull List<T> collection, @NotNull T toRemove) {
    List<T> result = ContainerUtil.newArrayList(collection);
    result.remove(toRemove);
    return result;
  }

  private int collectNumberOfPushedCommits(@NotNull VirtualFile root, @NotNull GitPushNativeResult result) {
    if (result.getType() != GitPushNativeResult.Type.SUCCESS) {
      return -1;
    }
    String range = result.getRange();
    if (range == null) {
      LOG.error("Range of pushed commits not reported in " + result);
      return -1;
    }
    try {
      return GitHistoryUtils.history(myProject, root, range).size();
    }
    catch (VcsException e) {
      LOG.error("Couldn't collect commits from range " + range);
      return -1;
    }
  }

  private void collectUpdatedFiles(@NotNull UpdatedFiles updatedFiles, @NotNull GitRepository repository,
                                   @NotNull String preUpdatePosition) {
    MergeChangeCollector collector = new MergeChangeCollector(myProject, repository.getRoot(), new GitRevisionNumber(preUpdatePosition));
    ArrayList<VcsException> exceptions = new ArrayList<VcsException>();
    collector.collect(updatedFiles, exceptions);
    for (VcsException exception : exceptions) {
      LOG.info(exception);
    }
  }

  @NotNull
  private Pair<List<GitPushNativeResult>, String> doPush(@NotNull GitRepository repository,
                                                         @NotNull PushSpec<GitPushSource, GitPushTarget> pushSpec) {
    GitPushTarget target = pushSpec.getTarget();
    GitLocalBranch sourceBranch = pushSpec.getSource().getBranch();
    GitRemoteBranch targetBranch = target.getBranch();

    GitLineHandlerListener progressListener = GitStandardProgressAnalyzer.createListener(myProgressIndicator);
    boolean setUpstream = pushSpec.getTarget().isNewBranchCreated() && !branchTrackingInfoIsSet(repository, sourceBranch);
    String tagMode = myTagMode == null ? null : myTagMode.getArgument();
    GitCommandResult res = myGit.push(repository, sourceBranch, targetBranch, myForce, setUpstream, tagMode, progressListener);

    List<GitPushNativeResult> result = GitPushNativeResultParser.parse(res.getOutput());
    if (result.isEmpty()) {
      return Pair.create(null, res.getErrorOutputAsJoinedString());
    }
    return Pair.create(result, null);
  }

  private static boolean branchTrackingInfoIsSet(@NotNull GitRepository repository, @NotNull final GitLocalBranch source) {
    return ContainerUtil.exists(repository.getBranchTrackInfos(), new Condition<GitBranchTrackInfo>() {
      @Override
      public boolean value(GitBranchTrackInfo info) {
        return info.getLocalBranch().equals(source);
      }
    });
  }

  private void savePushUpdateSettings(@NotNull PushUpdateSettings settings) {
    UpdateMethod updateMethod = settings.getUpdateMethod();
    myPushSettings.setUpdateAllRoots(settings.shouldUpdateAllRoots());
    myPushSettings.setUpdateMethod(updateMethod);
  }

  @NotNull
  private PushUpdateSettings readPushUpdateSettings() {
    boolean updateAllRoots = myPushSettings.shouldUpdateAllRoots();
    UpdateMethod updateMethod = myPushSettings.getUpdateMethod();
    return new PushUpdateSettings(updateAllRoots, updateMethod);
  }

  @Nullable
  private PushUpdateSettings showDialogAndGetExitCode(@NotNull final Set<GitRepository> repositories,
                                                      @NotNull final PushUpdateSettings initialSettings) {
    final Ref<PushUpdateSettings> updateSettings = Ref.create();
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        GitRejectedPushUpdateDialog dialog = new GitRejectedPushUpdateDialog(myProject, repositories, initialSettings);
        DialogManager.show(dialog);
        int exitCode = dialog.getExitCode();
        if (exitCode != DialogWrapper.CANCEL_EXIT_CODE) {
          mySettings.setAutoUpdateIfPushRejected(dialog.shouldAutoUpdateInFuture());
          updateSettings.set(new PushUpdateSettings(dialog.shouldUpdateAll(), convertUpdateMethodFromDialogExitCode(exitCode)));
        }
      }
    });
    return updateSettings.get();
  }

  @NotNull
  private static UpdateMethod convertUpdateMethodFromDialogExitCode(int exitCode) {
    switch (exitCode) {
      case GitRejectedPushUpdateDialog.MERGE_EXIT_CODE:  return UpdateMethod.MERGE;
      case GitRejectedPushUpdateDialog.REBASE_EXIT_CODE: return UpdateMethod.REBASE;
      default: throw new IllegalStateException("Unexpected exit code: " + exitCode);
    }
  }

  @NotNull
  protected GitUpdateResult update(@NotNull Collection<GitRepository> rootsToUpdate, @NotNull UpdateMethod updateMethod) {
    GitUpdateProcess.UpdateMethod um = updateMethod == UpdateMethod.MERGE ?
                                       GitUpdateProcess.UpdateMethod.MERGE :
                                       GitUpdateProcess.UpdateMethod.REBASE;
    GitUpdateResult updateResult = new GitUpdateProcess(myProject, myPlatformFacade, myProgressIndicator,
                                                        new HashSet<GitRepository>(rootsToUpdate), UpdatedFiles.create()).update(um);
    for (GitRepository repository : rootsToUpdate) {
      repository.getRoot().refresh(true, true);
      repository.update();
    }
    return updateResult;
  }

}
