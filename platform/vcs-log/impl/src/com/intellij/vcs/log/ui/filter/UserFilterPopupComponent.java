/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.vcs.log.ui.filter;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.VcsLogUserFilter;
import com.intellij.vcs.log.VcsUser;
import com.intellij.vcs.log.data.VcsLogDataHolder;
import com.intellij.vcs.log.data.VcsLogUiProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Show a popup to select a user or enter the user name.
 */
class UserFilterPopupComponent extends MultipleValueFilterPopupComponent<VcsLogUserFilter> {

  private static final String ME = "me";

  @NotNull private final VcsLogDataHolder myDataHolder;
  @NotNull private final VcsLogUiProperties myUiProperties;

  UserFilterPopupComponent(@NotNull VcsLogUiProperties uiProperties,
                           @NotNull VcsLogDataHolder dataHolder,
                           @NotNull FilterModel<VcsLogUserFilter> filterModel) {
    super("User", uiProperties, filterModel);
    myDataHolder = dataHolder;
    myUiProperties = uiProperties;
  }

  @NotNull
  @Override
  protected String getText(@NotNull VcsLogUserFilter filter) {
    return displayableText(getValues(filter));
  }

  @Nullable
  @Override
  protected String getToolTip(@NotNull VcsLogUserFilter filter) {
    return tooltip(getValues(filter));
  }

  @Override
  protected ActionGroup createActionGroup() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(createAllAction());
    group.add(createSelectMultipleValuesAction());
    if (!myDataHolder.getCurrentUser().isEmpty()) {
      group.add(createPredefinedValueAction(Collections.singleton(ME)));
    }
    group.addAll(createRecentItemsActionGroup());
    return group;
  }

  @NotNull
  @Override
  protected Collection<String> getValues(@Nullable VcsLogUserFilter filter) {
    if (filter == null) {
      return Collections.emptySet();
    }
    Set<String> result = ContainerUtil.newHashSet();
    for (VirtualFile root : myFilterModel.getDataPack().getLogProviders().keySet()) {
      result.addAll(filter.getUserNames(root));
    }
    return result;
  }

  @NotNull
  @Override
  protected List<List<String>> getRecentValuesFromSettings() {
    return myUiProperties.getRecentlyFilteredUserGroups();
  }

  @Override
  protected void rememberValuesInSettings(@NotNull Collection<String> values) {
    myUiProperties.addRecentlyFilteredUserGroup(new ArrayList<String>(values));
  }

  @NotNull
  @Override
  protected List<String> getAllValues() {
    return ContainerUtil.map(myDataHolder.getAllUsers(), new Function<VcsUser, String>() {
      @Override
      public String fun(VcsUser user) {
        return user.getName();
      }
    });
  }

  @NotNull
  @Override
  protected VcsLogUserFilter createFilter(@NotNull Collection<String> values) {
    return new VcsLogUserFilterImpl(values, myDataHolder.getCurrentUser());
  }

  private static class VcsLogUserFilterImpl implements VcsLogUserFilter {

    @NotNull private final Collection<String> myUsers;
    @NotNull private final Map<VirtualFile, VcsUser> myData;

    public VcsLogUserFilterImpl(@NotNull Collection<String> users, @NotNull Map<VirtualFile, VcsUser> meData) {
      myUsers = users;
      myData = meData;
    }

    @NotNull
    @Override
    public Collection<String> getUserNames(@NotNull final VirtualFile root) {
      return ContainerUtil.mapNotNull(myUsers, new Function<String, String>() {
        @Override
        public String fun(String user) {
          if (ME.equals(user)) {
            VcsUser vcsUser = myData.get(root);
            return vcsUser == null ? null : vcsUser.getName();
          }
          return user;
        }
      });
    }

    @Override
    public boolean matches(@NotNull final VcsCommitMetadata commit) {
      return ContainerUtil.exists(getUserNames(commit.getRoot()), new Condition<String>() {
        @SuppressWarnings("StringToUpperCaseOrToLowerCaseWithoutLocale")
        @Override
        public boolean value(String user) {
          String lowerUser = user.toLowerCase();
          return commit.getAuthor().getName().toLowerCase().contains(lowerUser) ||
                 commit.getAuthor().getEmail().toLowerCase().contains(lowerUser);
        }
      });
    }
  }
}
