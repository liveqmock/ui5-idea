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
package com.intellij.dvcs.push;

import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.push.ui.*;
import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcs.log.VcsFullCommitDetails;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class PushController implements Disposable {

  @NotNull private final Project myProject;
  @NotNull private final List<? extends Repository> myPreselectedRepositories;
  @NotNull private final List<PushSupport<?, ?, ?>> myPushSupports;
  @NotNull private final PushLog myPushLog;
  @NotNull private final VcsPushDialog myDialog;
  @NotNull private final PushSettings myExcludedSettings;
  @NotNull private final Set<String> myExcludedRepositoryRoots;
  @Nullable private final Repository myCurrentlyOpenedRepository;
  private boolean mySingleRepoProject;
  private static final int DEFAULT_CHILDREN_PRESENTATION_NUMBER = 20;
  private final ExecutorService myExecutorService = Executors.newSingleThreadExecutor();

  private final Map<RepositoryNode, MyRepoModel> myView2Model = new TreeMap<RepositoryNode, MyRepoModel>();
  //todo need to sort repositories in ui tree using natural order

  public PushController(@NotNull Project project,
                        @NotNull VcsPushDialog dialog,
                        @NotNull List<? extends Repository> preselectedRepositories, @Nullable Repository currentRepo) {
    myProject = project;
    myExcludedSettings = ServiceManager.getService(project, PushSettings.class);
    myExcludedRepositoryRoots = ContainerUtil.newHashSet(myExcludedSettings.getExcludedRepoRoots());
    myPreselectedRepositories = preselectedRepositories;
    myCurrentlyOpenedRepository = currentRepo;
    myPushSupports = getAffectedSupports(myProject);
    mySingleRepoProject = isSingleRepoProject(myPushSupports);
    CheckedTreeNode rootNode = new CheckedTreeNode(null);
    createTreeModel(rootNode);
    myPushLog = new PushLog(myProject, rootNode);
    myPushLog.getTree().addPropertyChangeListener(PushLogTreeUtil.EDIT_MODE_PROP, new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent evt) {
        Boolean isEditMode = (Boolean)evt.getNewValue();
        myDialog.enableOkActions(!isEditMode && isPushAllowed());
      }
    });
    myDialog = dialog;
    startLoadingCommits();
    Disposer.register(dialog.getDisposable(), this);
  }

  private static boolean isSingleRepoProject(@NotNull List<PushSupport<?, ?, ?>> pushSupports) {
    int repos = 0;
    for (PushSupport support : pushSupports) {
      repos += support.getRepositoryManager().getRepositories().size();
    }
    return repos == 1;
  }

  @NotNull
  private static List<PushSupport<?, ?, ?>> getAffectedSupports(@NotNull Project project) {
    return ContainerUtil.filter(Extensions.getExtensions(PushSupport.PUSH_SUPPORT_EP, project), new Condition<PushSupport>() {
      @Override
      public boolean value(PushSupport support) {
        return !support.getRepositoryManager().getRepositories().isEmpty();
      }
    });
  }

  public boolean isForcePushEnabled() {
    return ContainerUtil.exists(myView2Model.values(), new Condition<MyRepoModel>() {
      @Override
      public boolean value(MyRepoModel model) {
        return model.getSupport().isForcePushEnabled();
      }
    });
  }

  @Nullable
  public PushTarget getProhibitedTarget() {
    MyRepoModel model = ContainerUtil.find(myView2Model.values(), new Condition<MyRepoModel>() {
      @Override
      public boolean value(MyRepoModel model) {
        PushTarget target = model.getTarget();
        return model.isSelected() &&
               target != null && !model.getSupport().isForcePushAllowed(model.getRepository(), target);
      }
    });
    return model != null ? model.getTarget() : null;
  }

  private void startLoadingCommits() {
    Map<RepositoryNode, MyRepoModel> priorityLoading = ContainerUtil.newLinkedHashMap();
    Map<RepositoryNode, MyRepoModel> others = ContainerUtil.newLinkedHashMap();
    RepositoryNode nodeForCurrentEditor = findInfoByRepo(myCurrentlyOpenedRepository);
    for (Map.Entry<RepositoryNode, MyRepoModel> entry : myView2Model.entrySet()) {
      MyRepoModel model = entry.getValue();
      Repository repository = model.getRepository();
      RepositoryNode repoNode = entry.getKey();
      if (preselectByUser(repository)) {
        priorityLoading.put(repoNode, model);
      }
      else if (model.getSupport().shouldRequestIncomingChangesForNotCheckedRepositories() && !repoNode.equals(nodeForCurrentEditor)) {
        others.put(repoNode, model);
      }
    }
    if (nodeForCurrentEditor != null) {
      //add repo for currently opened editor to the end of priority queue
      priorityLoading.put(nodeForCurrentEditor, myView2Model.get(nodeForCurrentEditor));
    }
    loadCommitsFromMap(priorityLoading);
    loadCommitsFromMap(others);
  }

  @Nullable
  private RepositoryNode findInfoByRepo(@Nullable final Repository repository) {
    if (repository == null) return null;
    for (Map.Entry<RepositoryNode, MyRepoModel> entry : myView2Model.entrySet()) {
      MyRepoModel model = entry.getValue();
      if (model.getRepository().getRoot().equals(repository)) return entry.getKey();
    }
    return null;
  }

  private void loadCommitsFromMap(@NotNull Map<RepositoryNode, MyRepoModel> items) {
    for (Map.Entry<RepositoryNode, MyRepoModel> entry : items.entrySet()) {
      RepositoryNode node = entry.getKey();
      loadCommits(entry.getValue(), node, true);
    }
  }

  private void createTreeModel(@NotNull CheckedTreeNode rootNode) {
    for (PushSupport<? extends Repository, ? extends PushSource, ? extends PushTarget> support : myPushSupports) {
      createNodesForVcs(support, rootNode);
    }
  }

  private <R extends Repository, S extends PushSource, T extends PushTarget> void createNodesForVcs(
    @NotNull PushSupport<R, S, T> pushSupport, @NotNull CheckedTreeNode rootNode) {
    for (R repository : pushSupport.getRepositoryManager().getRepositories()) {
      createRepoNode(pushSupport, repository, rootNode);
    }
  }

  private <R extends Repository, S extends PushSource, T extends PushTarget> void createRepoNode(@NotNull final PushSupport<R, S, T> support,
                                                                                                 @NotNull final R repository,
                                                                                                 @NotNull final CheckedTreeNode rootNode) {
    T target = support.getDefaultTarget(repository);
    String repoName = getDisplayedRepoName(repository);
    S source = support.getSource(repository);
    final MyRepoModel<R, S, T> model = new MyRepoModel<R, S, T>(repository, support, mySingleRepoProject,
                                                                source, target,
                                                                DEFAULT_CHILDREN_PRESENTATION_NUMBER);
    if (target == null) {
      model.setError(VcsError.createEmptyTargetError(repoName));
    }

    final PushTargetPanel<T> pushTargetPanel = support.createTargetPanel(repository, target);
    final RepositoryWithBranchPanel<T> repoPanel = new RepositoryWithBranchPanel<T>(myProject, repoName,
                                                                                    source.getPresentation(), pushTargetPanel);
    CheckBoxModel checkBoxModel = model.getCheckBoxModel();
    final RepositoryNode repoNode = mySingleRepoProject
                                    ? new SingleRepositoryNode(repoPanel, checkBoxModel)
                                    : new RepositoryNode(repoPanel, checkBoxModel, target != null);
    pushTargetPanel.setFireOnChangeAction(new Runnable() {
      @Override
      public void run() {
        repoPanel.fireOnChange();
        ((DefaultTreeModel)myPushLog.getTree().getModel()).nodeChanged(repoNode); // tell the tree to repaint the changed node
      }
    });
    myView2Model.put(repoNode, model);
    repoPanel.addRepoNodeListener(new RepositoryNodeListener<T>() {
      @Override
      public void onTargetChanged(T newTarget) {
        repoNode.setChecked(true);
        myExcludedRepositoryRoots.remove(model.getRepository().getRoot().getPath());
        model.setTarget(newTarget);
        model.clearErrors();
        loadCommits(model, repoNode, false);
      }

      @Override
      public void onSelectionChanged(boolean isSelected) {
        myDialog.enableOkActions(isPushAllowed());
        if (isSelected) {
          boolean forceLoad = myExcludedRepositoryRoots.remove(model.getRepository().getRoot().getPath());
          if (!model.hasCommitInfo() && (forceLoad || !model.getSupport().shouldRequestIncomingChangesForNotCheckedRepositories())) {
            loadCommits(model, repoNode, false);
          }
        }
        else {
          myExcludedRepositoryRoots.add(model.getRepository().getRoot().getPath());
        }
      }
    });
    rootNode.add(repoNode);
  }

  // TODO This logic shall be moved to some common place and used instead of DvcsUtil.getShortRepositoryName
  @NotNull
  private String getDisplayedRepoName(@NotNull Repository repository) {
    String name = DvcsUtil.getShortRepositoryName(repository);
    int slash = name.lastIndexOf(File.separatorChar);
    if (slash < 0) {
      return name;
    }
    String candidate = name.substring(slash + 1);
    if (!getOtherReposLastNames(repository).contains(candidate)) {
      return candidate;
    }
    return name;
  }

  @NotNull
  private Set<String> getOtherReposLastNames(@NotNull Repository except) {
    Set<String> repos = ContainerUtil.newHashSet();
    for (PushSupport<?, ?, ?> support : myPushSupports) {
      for (Repository repo : support.getRepositoryManager().getRepositories()) {
        if (!repo.equals(except)) {
          repos.add(repo.getRoot().getName());
        }
      }
    }
    return repos;
  }

  public boolean isPushAllowed() {
    JTree tree = myPushLog.getTree();
    return !tree.isEditing() &&
           ContainerUtil.exists(myPushSupports, new Condition<PushSupport<?, ?, ?>>() {
             @Override
             public boolean value(PushSupport<?, ?, ?> support) {
               return isPushAllowed(support);
             }
           });
  }

  private boolean isPushAllowed(@NotNull PushSupport<?, ?, ?> pushSupport) {
    Collection<RepositoryNode> nodes = getNodesForSupport(pushSupport);
    if (hasSomethingToPush(nodes)) return true;
    if (hasCheckedNodesWithContent(nodes, myDialog.getAdditionalOptionValue(pushSupport) != null)) {
      return !pushSupport.getRepositoryManager().isSyncEnabled() || allNodesAreLoaded(nodes);
    }
    return false;
  }

  private boolean hasSomethingToPush(Collection<RepositoryNode> nodes) {
    return ContainerUtil.exists(nodes, new Condition<RepositoryNode>() {
      @Override
      public boolean value(@NotNull RepositoryNode node) {
        PushTarget target = myView2Model.get(node).getTarget();
        //if node is selected target should not be null
        return (node.isChecked() || node.isLoading()) && target != null && target.hasSomethingToPush();
      }
    });
  }

  private boolean hasCheckedNodesWithContent(@NotNull Collection<RepositoryNode> nodes, final boolean withRefs) {
    return ContainerUtil.exists(nodes, new Condition<RepositoryNode>() {
      @Override
      public boolean value(@NotNull RepositoryNode node) {
        return node.isChecked() && (withRefs || !myView2Model.get(node).getLoadedCommits().isEmpty());
      }
    });
  }

  @NotNull
  private Collection<RepositoryNode> getNodesForSupport(final PushSupport<?, ?, ?> support) {
    return ContainerUtil.mapNotNull(myView2Model.entrySet(), new Function<Map.Entry<RepositoryNode, MyRepoModel>, RepositoryNode>() {
      @Override
      public RepositoryNode fun(Map.Entry<RepositoryNode, MyRepoModel> entry) {
        return support.equals(entry.getValue().getSupport()) ? entry.getKey() : null;
      }
    });
  }


  private static boolean allNodesAreLoaded(@NotNull Collection<RepositoryNode> nodes) {
    return !ContainerUtil.exists(nodes, new Condition<RepositoryNode>() {
      @Override
      public boolean value(@NotNull RepositoryNode node) {
        return node.isLoading();
      }
    });
  }

  private <R extends Repository, S extends PushSource, T extends PushTarget> void loadCommits(@NotNull final MyRepoModel<R, S, T> model,
                                                                                              @NotNull final RepositoryNode node,
                                                                                              final boolean initial) {
    node.cancelLoading();
    final T target = model.getTarget();
    if (target == null) {
      node.stopLoading();
      return;
    }
    node.setEnabled(true);
    final PushSupport<R, S, T> support = model.getSupport();
    final AtomicReference<OutgoingResult> result = new AtomicReference<OutgoingResult>();
    Runnable task = new Runnable() {
      @Override
      public void run() {
        final R repository = model.getRepository();
        OutgoingResult outgoing = support.getOutgoingCommitsProvider()
          .getOutgoingCommits(repository, new PushSpec<S, T>(model.getSource(), model.getTarget()), initial);
        result.compareAndSet(null, outgoing);
        UIUtil.invokeAndWaitIfNeeded(new Runnable() {
          @Override
          public void run() {
            OutgoingResult outgoing = result.get();
            List<VcsError> errors = outgoing.getErrors();
            boolean shouldBeSelected;
            if (!errors.isEmpty()) {
              shouldBeSelected = false;
              model.setLoadedCommits(ContainerUtil.<VcsFullCommitDetails>emptyList());
              myPushLog.setChildren(node, ContainerUtil.map(errors, new Function<VcsError, DefaultMutableTreeNode>() {
                @Override
                public DefaultMutableTreeNode fun(final VcsError error) {
                  VcsLinkedTextComponent errorLinkText = new VcsLinkedTextComponent(error.getText(), new VcsLinkListener() {
                    @Override
                    public void hyperlinkActivated(@NotNull DefaultMutableTreeNode sourceNode, @NotNull MouseEvent event) {
                      error.handleError(new CommitLoader() {
                        @Override
                        public void reloadCommits() {
                          loadCommits(model, node, false);
                        }
                      });
                    }
                  });
                  return new TextWithLinkNode(errorLinkText);
                }
              }));
            }
            else {
              List<? extends VcsFullCommitDetails> commits = outgoing.getCommits();
              model.setLoadedCommits(commits);
              shouldBeSelected = shouldSelect(model);
              myPushLog.setChildren(node,
                                    getPresentationForCommits(PushController.this.myProject, model.getLoadedCommits(),
                                                              model.getNumberOfShownCommits()));
              if (!commits.isEmpty()) {
                myPushLog.selectIfNothingSelected(node);
              }
            }
            node.stopLoading();
            if (shouldBeSelected) { // never remove selection; initially all checkboxes are not selected
              node.setChecked(true);
            }
            myDialog.enableOkActions(isPushAllowed());
          }
        });
      }
    };
    node.startLoading(myPushLog.getTree(), myExecutorService.submit(task, result));
  }

  private boolean shouldSelect(@NotNull MyRepoModel model) {
    if (mySingleRepoProject) return true;
    Repository repository = model.getRepository();
    return hasCommitsToPush(model) && (preselectByUser(repository) || notExcludedByUser(repository));
  }

  private boolean notExcludedByUser(@NotNull Repository repository) {
    return !myExcludedRepositoryRoots.contains(repository.getRoot().getPath());
  }

  private boolean preselectByUser(@NotNull Repository repository) {
    return myPreselectedRepositories.contains(repository);
  }

  private static boolean hasCommitsToPush(@NotNull MyRepoModel model) {
    PushTarget target = model.getTarget();
    assert target != null;
    return (!model.getLoadedCommits().isEmpty() || target.hasSomethingToPush());
  }

  public PushLog getPushPanelLog() {
    return myPushLog;
  }

  public void push(final boolean force) {
    Task.Backgroundable task = new Task.Backgroundable(myProject, "Pushing...", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        myExcludedSettings.saveExcludedRepoRoots(myExcludedRepositoryRoots);
        for (PushSupport support : myPushSupports) {
          doPush(support, force);
        }
      }
    };
    task.queue();
  }

  private <R extends Repository, S extends PushSource, T extends PushTarget> void doPush(@NotNull PushSupport<R, S, T> support,
                                                                                         boolean force) {
    VcsPushOptionValue options = myDialog.getAdditionalOptionValue(support);
    Pusher<R, S, T> pusher = support.getPusher();
    Map<R, PushSpec<S, T>> specs = collectPushSpecsForVcs(support);
    if (!specs.isEmpty()) {
      pusher.push(specs, options, force);
    }
  }

  @NotNull
  private <R extends Repository, S extends PushSource, T extends PushTarget> Map<R, PushSpec<S, T>> collectPushSpecsForVcs(@NotNull PushSupport<R, S, T> pushSupport) {
    Map<R, PushSpec<S, T>> pushSpecs = ContainerUtil.newHashMap();
    Collection<MyRepoModel> repositoriesInformation = getSelectedRepoNode();
    for (MyRepoModel repoModel : repositoriesInformation) {
      if (pushSupport.equals(repoModel.getSupport())) {
        //todo improve generics: unchecked casts
        T target = (T)repoModel.getTarget();
        if (target != null) {
          pushSpecs.put((R)repoModel.getRepository(), new PushSpec<S, T>((S)repoModel.getSource(), target));
        }
      }
    }
    return pushSpecs;
  }

  public Collection<MyRepoModel> getSelectedRepoNode() {
    if (mySingleRepoProject) {
      return myView2Model.values();
    }
    return ContainerUtil.filter(myView2Model.values(), new Condition<MyRepoModel>() {
      @Override
      public boolean value(MyRepoModel model) {
        return model.isSelected();
      }
    });
  }

  @Override
  public void dispose() {
    myExecutorService.shutdownNow();
  }

  private void addMoreCommits(RepositoryNode repositoryNode) {
    MyRepoModel repoModel = myView2Model.get(repositoryNode);
    repoModel.increaseShownCommits();
    myPushLog.setChildren(repositoryNode,
                          getPresentationForCommits(
                            myProject,
                            repoModel.getLoadedCommits(),
                            repoModel.getNumberOfShownCommits()
                          ));
  }


  @NotNull
  public List<DefaultMutableTreeNode> getPresentationForCommits(@NotNull final Project project,
                                                                @NotNull List<? extends VcsFullCommitDetails> commits,
                                                                int commitsNum) {
    Function<VcsFullCommitDetails, DefaultMutableTreeNode> commitToNode = new Function<VcsFullCommitDetails, DefaultMutableTreeNode>() {
      @Override
      public DefaultMutableTreeNode fun(VcsFullCommitDetails commit) {
        return new CommitNode(project, commit);
      }
    };
    List<DefaultMutableTreeNode> childrenToShown = new ArrayList<DefaultMutableTreeNode>();
    for (int i = 0; i < commits.size(); ++i) {
      if (i >= commitsNum) {
        final VcsLinkedTextComponent moreCommitsLink = new VcsLinkedTextComponent("<a href='loadMore'>...</a>", new VcsLinkListener() {
          @Override
          public void hyperlinkActivated(@NotNull DefaultMutableTreeNode sourceNode, @NotNull MouseEvent event) {
            TreeNode parent = sourceNode.getParent();
            if (parent instanceof RepositoryNode) {
              addMoreCommits((RepositoryNode)parent);
            }
          }
        });
        childrenToShown.add(new TextWithLinkNode(moreCommitsLink));
        break;
      }
      childrenToShown.add(commitToNode.fun(commits.get(i)));
    }
    return childrenToShown;
  }

  @NotNull
  public Map<PushSupport, VcsPushOptionsPanel> createAdditionalPanels() {
    Map<PushSupport, VcsPushOptionsPanel> result = ContainerUtil.newLinkedHashMap();
    for (PushSupport support : myPushSupports) {
      ContainerUtil.putIfNotNull(support, support.createOptionsPanel(), result);
    }
    return result;
  }

  private static class MyRepoModel<Repo extends Repository, S extends PushSource, T extends PushTarget> {
    @NotNull final Repo myRepository;
    @NotNull private PushSupport<Repo, S, T> mySupport;
    @NotNull private final S mySource;
    @Nullable private T myTarget;
    @Nullable VcsError myTargetError;

    int myNumberOfShownCommits;
    @NotNull List<? extends VcsFullCommitDetails> myLoadedCommits = Collections.emptyList();
    @NotNull CheckBoxModel myCheckBoxModel;

    public MyRepoModel(@NotNull Repo repository,
                       @NotNull PushSupport<Repo, S, T> supportForRepo,
                       boolean isSelected, @NotNull S source, @Nullable T target,
                       int num) {
      myRepository = repository;
      mySupport = supportForRepo;
      myCheckBoxModel = new CheckBoxModel(isSelected);
      mySource = source;
      myTarget = target;
      myNumberOfShownCommits = num;
    }

    @NotNull
    public Repo getRepository() {
      return myRepository;
    }

    @NotNull
    public PushSupport<Repo, S, T> getSupport() {
      return mySupport;
    }

    @NotNull
    public S getSource() {
      return mySource;
    }

    @Nullable
    public T getTarget() {
      return myTarget;
    }

    public void setTarget(@Nullable T target) {
      myTarget = target;
    }

    public boolean isSelected() {
      return myCheckBoxModel.isChecked();
    }

    public AbstractVcs<?> getVcs() {
      return myRepository.getVcs();
    }

    @Nullable
    public VcsError getError() {
      return myTargetError;
    }

    public void setError(@Nullable VcsError error) {
      myTargetError = error;
    }

    public void clearErrors() {
      myTargetError = null;
    }

    public boolean hasError() {
      return myTargetError != null;
    }

    public int getNumberOfShownCommits() {
      return myNumberOfShownCommits;
    }

    public void increaseShownCommits() {
      myNumberOfShownCommits *= 2;
    }

    @NotNull
    public List<? extends VcsFullCommitDetails> getLoadedCommits() {
      return myLoadedCommits;
    }

    public void setLoadedCommits(@NotNull List<? extends VcsFullCommitDetails> loadedCommits) {
      myLoadedCommits = loadedCommits;
    }

    public boolean hasCommitInfo() {
      return myTargetError != null || !myLoadedCommits.isEmpty();
    }

    @NotNull
    public CheckBoxModel getCheckBoxModel() {
      return myCheckBoxModel;
    }
  }
}
